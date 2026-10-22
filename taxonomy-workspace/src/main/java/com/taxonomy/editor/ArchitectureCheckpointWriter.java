package com.taxonomy.editor;

import com.taxonomy.workspace.service.BranchHeadConflictException;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.ExpectedHeadDslCommitter;
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
        // A checkpoint is one Git publication, even when several requests retry its
        // durable intent. Keep insertion and ref publication together on this live
        // repository: storage 0.11.3 otherwise takes its catalogue and database locks
        // in opposite orders when another retry flushes a pack during a ref update.
        // Separate processes still coordinate through the expected-head CAS below;
        // this monitor neither replaces that check nor spans a journal transaction.
        synchronized (repository) {
            return write(source, repository, scope, branch, request);
        }
    }

    private Result write(DslGitRepository source, Repository repository, String scope, String branch,
                         EditorJournal.Checkpoint request) throws IOException {
        String previous = request.expectedCommit();
        if (Objects.equals(request.dsl(), previous == null ? "" : source.getDslAtCommit(previous))) {
            new ExpectedHeadDslCommitter().verifyExpectedHead(source, branch, previous);
            return new Result(previous, false);
        }
        // The unsigned payload comes from the durable intent. Signatures may
        // differ after key rotation or when a background worker resumes a request.
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
            String recovered = findPublishedCheckpoint(source, repository, branch, commit);
            if (recovered != null) return new Result(recovered, true);
            source.signCommit(commit, branch);
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            if (applied(repository, branch, id)) return new Result(id.name(), true);
            try {
                new ExpectedHeadDslCommitter().verifyExpectedHead(source, branch, previous);
            } catch (BranchHeadConflictException conflict) {
                // A concurrent retry of this SAME durable intent may have won between the read and CAS.
                recovered = findPublishedCheckpoint(source, repository, branch, commit);
                if (recovered != null) return new Result(recovered, true);
                throw conflict;
            }
            RefUpdate update = repository.updateRef(Constants.R_HEADS + branch);
            update.setExpectedOldObjectId(previous == null ? ObjectId.zeroId() : ObjectId.fromString(previous));
            update.setNewObjectId(id); update.setForceUpdate(false);
            update.setRefLogIdent(actor); update.setRefLogMessage("checkpoint: " + request.commandId(), false);
            RefUpdate.Result result = update.update();
            if (result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD || result == RefUpdate.Result.NO_CHANGE) {
                return new Result(id.name(), true);
            }
            recovered = findPublishedCheckpoint(source, repository, branch, commit);
            if (recovered != null) return new Result(recovered, true);
            throw new IOException("Checkpoint ref update did not succeed: " + result + "; retry checkpoint " + request.commandId());
        }
    }

    /** Recover the exact durable payload without requiring deterministic signature bytes. */
    private static String findPublishedCheckpoint(DslGitRepository source, Repository repository,
                                                   String branch, CommitBuilder expected) throws IOException {
        Ref current = repository.exactRef(Constants.R_HEADS + branch);
        if (current == null || current.getObjectId() == null) return null;
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(current.getObjectId()));
            for (ObjectId parent : expected.getParentIds()) walk.markUninteresting(walk.parseCommit(parent));
            for (var candidate : walk) {
                if (!candidate.getTree().equals(expected.getTreeId())
                        || !candidate.getAuthorIdent().equals(expected.getAuthor())
                        || !candidate.getCommitterIdent().equals(expected.getCommitter())
                        || !candidate.getFullMessage().equals(expected.getMessage())
                        || candidate.getParentCount() != expected.getParentIds().length) continue;
                boolean parentsMatch = true;
                for (int i = 0; i < candidate.getParentCount(); i++) {
                    if (!candidate.getParent(i).equals(expected.getParentIds()[i])) parentsMatch = false;
                }
                if (parentsMatch) {
                    source.verifySnapshot(candidate.name());
                    return candidate.name();
                }
            }
        }
        return null;
    }

    private static boolean applied(Repository repository, String branch, ObjectId id) throws IOException {
        Ref current = repository.exactRef(Constants.R_HEADS + branch);
        if (current == null) return false;
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.isMergedInto(walk.parseCommit(id), walk.parseCommit(current.getObjectId()));
        }
    }
}
