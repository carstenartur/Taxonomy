package com.taxonomy.dsl.storage;

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
import java.util.Objects;

/**
 * Creates one DSL commit only when the selected branch still has the caller's
 * expected head.
 *
 * <p>The comparison is performed both before object insertion and atomically by
 * JGit's ref update. A concurrent change can therefore never be adopted as an
 * implicit parent of a relation command. Inserted objects may become unreachable
 * after a race, which is normal Git behaviour; the branch remains untouched.</p>
 */
public final class ExpectedHeadDslCommitter {

    public CommitResult commit(
            DslGitRepository dslRepository,
            CommitRequest request) throws IOException {
        Objects.requireNonNull(dslRepository, "dslRepository");
        Objects.requireNonNull(request, "request");

        Repository repository = dslRepository.getGitRepository();
        String refName = Constants.R_HEADS + request.branch();
        Ref branchRef = repository.getRefDatabase().exactRef(refName);
        ObjectId actualHead = branchRef == null || branchRef.getObjectId() == null
                ? ObjectId.zeroId()
                : branchRef.getObjectId();
        ObjectId expectedHead = request.expectedHeadObjectId();
        if (!actualHead.equals(expectedHead)) {
            throw conflict(request.branch(), expectedHead, actualHead, null);
        }

        PersonIdent actor = personIdent(request.author());
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId blobId = inserter.insert(
                    Constants.OBJ_BLOB,
                    request.dslText().getBytes(StandardCharsets.UTF_8));
            TreeFormatter tree = new TreeFormatter();
            tree.append(DslGitRepository.DSL_FILENAME,
                    FileMode.REGULAR_FILE, blobId);
            ObjectId treeId = inserter.insert(tree);

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(actor);
            commit.setCommitter(actor);
            commit.setMessage(request.message());
            if (!ObjectId.zeroId().equals(expectedHead)) {
                commit.setParentId(expectedHead);
            }

            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate update = repository.updateRef(refName);
            update.setNewObjectId(commitId);
            update.setExpectedOldObjectId(expectedHead);
            update.setForceUpdate(false);
            update.setRefLogIdent(actor);
            update.setRefLogMessage("commit: " + request.message(), true);
            RefUpdate.Result result = update.update();
            switch (result) {
                case NEW, FAST_FORWARD, FORCED -> {
                    return new CommitResult(
                            commitId.name(),
                            headName(expectedHead),
                            result);
                }
                case LOCK_FAILURE, REJECTED, REJECTED_CURRENT_BRANCH -> {
                    ObjectId currentHead = currentHead(repository, refName);
                    throw conflict(
                            request.branch(), expectedHead, currentHead, result);
                }
                default -> throw new IOException(
                        "Ref update failed for " + refName + ": " + result);
            }
        }
    }

    private static ObjectId currentHead(
            Repository repository,
            String refName) throws IOException {
        Ref current = repository.getRefDatabase().exactRef(refName);
        return current == null || current.getObjectId() == null
                ? ObjectId.zeroId()
                : current.getObjectId();
    }

    private static BranchHeadConflictException conflict(
            String branch,
            ObjectId expected,
            ObjectId actual,
            RefUpdate.Result result) {
        String suffix = result == null ? "" : " (ref update: " + result + ")";
        return new BranchHeadConflictException(
                branch,
                headName(expected),
                headName(actual),
                "Branch '" + branch + "' moved: expected "
                        + headName(expected) + ", actual " + headName(actual)
                        + suffix);
    }

    private static String headName(ObjectId id) {
        return id == null || ObjectId.zeroId().equals(id) ? null : id.name();
    }

    private static PersonIdent personIdent(String author) {
        String normalized = author == null || author.isBlank()
                ? "taxonomy"
                : author.strip();
        String email = normalized.contains("@")
                ? normalized
                : normalized + "@taxonomy.local";
        return new PersonIdent(normalized, email);
    }

    public record CommitRequest(
            String branch,
            String expectedHeadCommit,
            String dslText,
            String author,
            String message) {

        public CommitRequest {
            if (branch == null || branch.isBlank()) {
                throw new IllegalArgumentException("branch must not be blank");
            }
            branch = branch.strip();
            if (!Repository.isValidRefName(Constants.R_HEADS + branch)) {
                throw new IllegalArgumentException(
                        "invalid branch name: " + branch);
            }
            if (expectedHeadCommit != null) {
                expectedHeadCommit = expectedHeadCommit.strip();
                if (expectedHeadCommit.isEmpty()) {
                    throw new IllegalArgumentException(
                            "expectedHeadCommit must be null or a full commit ID");
                }
                ObjectId.fromString(expectedHeadCommit);
            }
            dslText = Objects.requireNonNull(dslText, "dslText");
            author = author == null || author.isBlank()
                    ? "taxonomy"
                    : author.strip();
            message = message == null || message.isBlank()
                    ? "DSL update"
                    : message.strip();
        }

        ObjectId expectedHeadObjectId() {
            return expectedHeadCommit == null
                    ? ObjectId.zeroId()
                    : ObjectId.fromString(expectedHeadCommit);
        }
    }

    public record CommitResult(
            String commitId,
            String previousHeadCommit,
            RefUpdate.Result refUpdateResult) {
    }

    public static final class BranchHeadConflictException extends IOException {
        private final String branch;
        private final String expectedHeadCommit;
        private final String actualHeadCommit;

        BranchHeadConflictException(
                String branch,
                String expectedHeadCommit,
                String actualHeadCommit,
                String message) {
            super(message);
            this.branch = branch;
            this.expectedHeadCommit = expectedHeadCommit;
            this.actualHeadCommit = actualHeadCommit;
        }

        public String getBranch() {
            return branch;
        }

        public String getExpectedHeadCommit() {
            return expectedHeadCommit;
        }

        public String getActualHeadCommit() {
            return actualHeadCommit;
        }
    }
}
