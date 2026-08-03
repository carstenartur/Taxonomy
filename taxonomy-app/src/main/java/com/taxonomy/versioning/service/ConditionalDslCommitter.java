package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ConcurrentModificationException;
import java.util.Objects;

/** Creates a DSL commit only when the target branch still has the expected HEAD. */
final class ConditionalDslCommitter {

    private static final String DSL_FILENAME = "architecture.taxdsl";

    String commit(DslGitRepository repository,
                  String branch,
                  String expectedHead,
                  String dsl,
                  String author,
                  String message) throws IOException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(expectedHead, "expectedHead");
        Objects.requireNonNull(dsl, "dsl");

        Repository git = repository.getGitRepository();
        String refName = Constants.R_HEADS + branch;
        Ref ref = git.getRefDatabase().exactRef(refName);
        String actualHead = ref != null && ref.getObjectId() != null
                ? ref.getObjectId().name() : null;
        if (!expectedHead.equals(actualHead)) {
            throw new ConcurrentModificationException(
                    "Target branch '" + branch + "' moved from " + expectedHead
                            + " to " + actualHead);
        }

        PersonIdent actor = new PersonIdent(
                author != null && !author.isBlank() ? author : "taxonomy",
                author != null && !author.isBlank() ? author : "taxonomy@system");
        try (ObjectInserter inserter = git.newObjectInserter()) {
            ObjectId blob = inserter.insert(
                    Constants.OBJ_BLOB, dsl.getBytes(StandardCharsets.UTF_8));
            TreeFormatter tree = new TreeFormatter();
            tree.append(DSL_FILENAME, FileMode.REGULAR_FILE, blob);
            ObjectId treeId = inserter.insert(tree);

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setParentId(ref.getObjectId());
            commit.setAuthor(actor);
            commit.setCommitter(actor);
            commit.setMessage(message != null ? message : "Selective transfer");
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate update = git.updateRef(refName);
            update.setNewObjectId(commitId);
            update.setExpectedOldObjectId(ObjectId.fromString(expectedHead));
            update.setRefLogIdent(actor);
            update.setRefLogMessage("selective-transfer: " + commit.getMessage(), true);
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.FAST_FORWARD
                    && result != RefUpdate.Result.NEW
                    && result != RefUpdate.Result.NO_CHANGE) {
                throw new ConcurrentModificationException(
                        "Target branch '" + branch + "' changed during transfer: " + result);
            }
            return commitId.name();
        }
    }
}
