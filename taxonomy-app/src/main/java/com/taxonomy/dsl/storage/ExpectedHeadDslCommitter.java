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
        ObjectId expectedHead = request.expectedHeadObjectId();
        ObjectId actualHead = currentHead(repository, refName);
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

    /**
     * Verifies that a semantic no-op still refers to the exact selected branch
     * head and therefore may safely return that commit as the authority token.
     *
     * <p>For an existing branch the verification uses a no-change ref update
     * with an expected-old-object precondition, so a concurrent writer cannot be
     * silently accepted. For an expected absent branch JGit has no object ID to
     * lock; the method performs a fail-closed exact-ref check and returns
     * {@code null} only while the branch remains absent.</p>
     */
    public String verifyExpectedHead(
            DslGitRepository dslRepository,
            String branch,
            String expectedHeadCommit) throws IOException {
        Objects.requireNonNull(dslRepository, "dslRepository");
        String normalizedBranch = normalizeBranch(branch);
        ObjectId expectedHead = expectedHeadObjectId(expectedHeadCommit);
        Repository repository = dslRepository.getGitRepository();
        String refName = Constants.R_HEADS + normalizedBranch;
        ObjectId actualHead = currentHead(repository, refName);
        if (!actualHead.equals(expectedHead)) {
            throw conflict(normalizedBranch, expectedHead, actualHead, null);
        }
        if (ObjectId.zeroId().equals(expectedHead)) {
            return null;
        }

        RefUpdate update = repository.updateRef(refName);
        update.setNewObjectId(expectedHead);
        update.setExpectedOldObjectId(expectedHead);
        update.setForceUpdate(false);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        if (result == RefUpdate.Result.NO_CHANGE) {
            return expectedHead.name();
        }
        if (result == RefUpdate.Result.LOCK_FAILURE
                || result == RefUpdate.Result.REJECTED
                || result == RefUpdate.Result.REJECTED_CURRENT_BRANCH) {
            throw conflict(
                    normalizedBranch,
                    expectedHead,
                    currentHead(repository, refName),
                    result);
        }
        throw new IOException(
                "Expected-head verification failed for " + refName + ": " + result);
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

    private static String normalizeBranch(String branch) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("branch must not be blank");
        }
        String normalized = branch.strip();
        if (!Repository.isValidRefName(Constants.R_HEADS + normalized)) {
            throw new IllegalArgumentException(
                    "invalid branch name: " + normalized);
        }
        return normalized;
    }

    private static ObjectId expectedHeadObjectId(String expectedHeadCommit) {
        if (expectedHeadCommit == null) {
            return ObjectId.zeroId();
        }
        String normalized = expectedHeadCommit.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedHeadCommit must be null or a full commit ID");
        }
        return ObjectId.fromString(normalized);
    }

    public record CommitRequest(
            String branch,
            String expectedHeadCommit,
            String dslText,
            String author,
            String message) {

        public CommitRequest {
            branch = normalizeBranch(branch);
            if (expectedHeadCommit != null) {
                expectedHeadCommit = expectedHeadCommit.strip();
                expectedHeadObjectId(expectedHeadCommit);
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
            return ExpectedHeadDslCommitter.expectedHeadObjectId(
                    expectedHeadCommit);
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
