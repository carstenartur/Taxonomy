package com.taxonomy.editor;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter;
import com.taxonomy.editor.persistence.EditorJournal;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/** Retryable Git phase of a durable checkpoint intent. Deliberately outside the journal transaction. */
@Component
public class ArchitectureCheckpointWriter {
    public record Result(String commitId, boolean created) {}

    public Result write(DslGitRepository source, String scope, String branch, EditorJournal.Checkpoint request) throws IOException {
        Repository repository = source.getGitRepository();
        String previous = request.expectedCommit();
        if (Objects.equals(request.dsl(), previous == null ? "" : source.getDslAtCommit(previous))) {
            new ExpectedHeadDslCommitter().verifyExpectedHead(source, branch, previous);
            return new Result(previous, false);
        }
        // All commit bytes, including the timestamp, come from the durable intent.
        // A retry after ref update but before journal completion recreates the same object ID.
        PersonIdent actor = new PersonIdent(request.actor(), request.actor().contains("@")
                ? request.actor() : request.actor() + "@taxonomy.local", Instant.parse(request.occurredAt()), ZoneOffset.UTC);
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, request.dsl().getBytes(StandardCharsets.UTF_8));
            TreeFormatter tree = new TreeFormatter();
            tree.append(DslGitRepository.DSL_FILENAME, FileMode.REGULAR_FILE, blob);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(tree));
            commit.setAuthor(actor); commit.setCommitter(actor);
            if (previous != null) commit.setParentId(ObjectId.fromString(previous));
            commit.setMessage("architecture checkpoint: " + request.rationale()
                    + "\n\nCheckpoint-Id: " + request.commandId() + "\nCheckpoint-Scope: " + scope
                    + "\nCheckpoint-Fingerprint: " + request.fingerprint()
                    + "\nSemantic-Revision: " + request.revision()
                    + "\nOperations-After: " + request.fromRevision() + "\nOperations-Through: " + request.revision()
                    + "\nRationale: " + request.rationale());
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            Ref current = repository.exactRef(Constants.R_HEADS + branch);
            if (current != null) {
                try (RevWalk walk = new RevWalk(repository)) {
                    if (walk.isMergedInto(walk.parseCommit(id), walk.parseCommit(current.getObjectId()))) {
                        return new Result(id.name(), true);
                    }
                }
            }
            new ExpectedHeadDslCommitter().verifyExpectedHead(source, branch, previous);
            RefUpdate update = repository.updateRef(Constants.R_HEADS + branch);
            update.setExpectedOldObjectId(previous == null ? ObjectId.zeroId() : ObjectId.fromString(previous));
            update.setNewObjectId(id); update.setForceUpdate(false);
            update.setRefLogIdent(actor); update.setRefLogMessage("checkpoint: " + request.commandId(), false);
            RefUpdate.Result result = update.update();
            if (result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD || result == RefUpdate.Result.NO_CHANGE) {
                return new Result(id.name(), true);
            }
            throw new IOException("Checkpoint ref update did not succeed: " + result + "; retry checkpoint " + request.commandId());
        }
    }
}
