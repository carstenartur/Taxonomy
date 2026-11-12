package com.taxonomy.workspace.storage;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.service.BranchHeadConflictException;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

import java.io.IOException;
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

        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId tree = dslRepository.insertDslTree(inserter, request.dslText(), request.provenance());
            ObjectId candidate = insertCommit(dslRepository, inserter, request, tree);
            inserter.flush();
            return updateBranch(repository, request.branch(), expectedHead, candidate, personIdent(request.author()), request.message());
        }
    }

    private static ObjectId insertCommit(DslGitRepository storage, ObjectInserter inserter, CommitRequest request,
                                         ObjectId tree) throws IOException {
        PersonIdent actor = personIdent(request.author());
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        commit.setAuthor(actor);
        commit.setCommitter(actor);
        commit.setMessage(request.message());
        if (request.expectedHeadCommit() != null) commit.setParentId(request.expectedHeadObjectId());
        for (String parent : request.additionalParents()) commit.addParentId(ObjectId.fromString(parent));
        storage.signCommit(commit, request.branch());
        return inserter.insert(commit);
    }

    private static CommitResult updateBranch(Repository repository, String branch, ObjectId expected, ObjectId candidate,
                                             PersonIdent actor, String message) throws IOException {
        String refName = Constants.R_HEADS + branch;
        RefUpdate update = repository.updateRef(refName);
        update.setNewObjectId(candidate);
        update.setExpectedOldObjectId(expected);
        update.setForceUpdate(false);
        update.setRefLogIdent(actor);
        update.setRefLogMessage("commit: " + message, true);
        var result = update.update();
        return switch (result) {
            case NEW, FAST_FORWARD, FORCED -> new CommitResult(candidate.name(), headName(expected), result);
            case LOCK_FAILURE, REJECTED, REJECTED_CURRENT_BRANCH ->
                    throw conflict(branch, expected, currentHead(repository, refName), result);
            default -> throw new IOException("Ref update failed for " + refName + ": " + result);
        };
    }

    String prepare(DslGitRepository storage, String operationId, CommitRequest request) throws IOException {
        Repository repo = storage.getGitRepository();
        String ref = pinRef(operationId, request.branch());
        var existing = readPin(repo, ref, request.branch());
        try (var inserter = repo.newObjectInserter()) {
            if (existing == null) {
                verifyExpectedHead(storage, request.branch(), request.expectedHeadCommit());
                for (String parent : request.additionalParents()) storage.verifyIncoming(parent, request.expectedHeadCommit());
            }
            ObjectId tree = storage.insertDslTree(inserter, request.dslText(), request.provenance());
            if (existing == null) {
                ObjectId candidate = insertCommit(storage, inserter, request, tree);
                existing = bind(repo, inserter, ref, request.branch(), request.expectedHeadObjectId(), candidate, personIdent(request.author()));
            }
            if (!existing.expected().equals(request.expectedHeadObjectId()) || !matchesRequest(existing.commit(), request, tree)) {
                throw new IOException("The operation already pins a different Git command for this branch");
            }
            return existing.commit().name();
        }
    }

    private static boolean matchesRequest(RevCommit commit, CommitRequest request, ObjectId tree) {
        var parents = new java.util.ArrayList<ObjectId>();
        if (request.expectedHeadCommit() != null) parents.add(request.expectedHeadObjectId());
        for (String parent : request.additionalParents()) parents.add(ObjectId.fromString(parent));
        var actor = personIdent(request.author());
        return commit.getTree().equals(tree) && commit.getFullMessage().equals(request.message())
                && commit.getAuthorIdent().getName().equals(actor.getName())
                && commit.getAuthorIdent().getEmailAddress().equals(actor.getEmailAddress())
                && java.util.Arrays.stream(commit.getParents()).map(RevCommit::getId).toList().equals(parents);
    }

    String pinExisting(DslGitRepository storage, String operationId, String branch, String oldHead, String candidateId)
            throws IOException {
        branch = normalizeBranch(branch);
        Repository repo = storage.getGitRepository();
        String ref = pinRef(operationId, branch);
        ObjectId expected = expectedHeadObjectId(oldHead);
        ObjectId candidate = ObjectId.fromString(candidateId);
        var pin = readPin(repo, ref, branch);
        if (pin == null) {
            verifyExpectedHead(storage, branch, oldHead);
            storage.verifyIncoming(candidate.name(), headName(expected));
            if (oldHead != null && !storage.isAncestor(expected.name(), candidate.name())) {
                throw new IOException("Prepared fast-forward must descend from the expected branch head");
            }
            try (var walk = new RevWalk(repo); var inserter = repo.newObjectInserter()) {
                var commit = walk.parseCommit(candidate);
                pin = bind(repo, inserter, ref, branch, expected, candidate, commit.getCommitterIdent());
            }
        }
        requirePin(pin, expected, candidate);
        return candidate.name();
    }

    String findPrepared(Repository repo, String operationId, String branch) throws IOException {
        branch = normalizeBranch(branch);
        var pin = readPin(repo, pinRef(operationId, branch), branch);
        return pin == null ? null : pin.commit().name();
    }

    String publish(DslGitRepository storage, String operationId, String branch, String oldHead, String candidateId)
            throws IOException {
        branch = normalizeBranch(branch);
        Repository repo = storage.getGitRepository();
        ObjectId candidate = ObjectId.fromString(candidateId);
        ObjectId expected = expectedHeadObjectId(oldHead);
        var pin = readPin(repo, pinRef(operationId, branch), branch);
        requirePin(pin, expected, candidate);
        storage.verifySnapshot(candidate.name());
        if (currentHead(repo, Constants.R_HEADS + branch).equals(candidate)) {
            return verifyExpectedHead(storage, branch, candidate.name());
        }
        try {
            return updateBranch(repo, branch, expected, candidate, pin.commit().getCommitterIdent(),
                    pin.commit().getFullMessage()).commitId();
        } catch (BranchHeadConflictException concurrent) {
            if (!candidate.name().equals(concurrent.getActualHeadCommit())) throw concurrent;
            return verifyExpectedHead(storage, branch, candidate.name());
        }
    }

    void release(Repository repo, String operationId, String branch, String candidateId) throws IOException {
        branch = normalizeBranch(branch);
        String ref = pinRef(operationId, branch);
        ObjectId candidate = ObjectId.fromString(candidateId);
        var pin = readPin(repo, ref, branch);
        if (pin == null) return;
        if (!pin.commit().equals(candidate)) throw new IOException("Prepared Git candidate does not match the pin");
        RefUpdate update = repo.updateRef(ref, true);
        update.setExpectedOldObjectId(pin.tagId());
        update.setForceUpdate(true);
        var result = update.delete();
        if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NO_CHANGE && result != RefUpdate.Result.NEW
                && readPin(repo, ref, branch) != null) {
            throw new IOException("Could not release prepared Git candidate: " + result);
        }
    }

    private static void requirePin(Pin pin, ObjectId expected, ObjectId candidate) throws IOException {
        if (pin == null || !pin.expected().equals(expected) || !pin.commit().equals(candidate)) {
            throw new IOException("The expected branch head and Git candidate must match the retained operation");
        }
    }

    private static String pinRef(String operationId, String branch) {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId must not be blank");
        return "refs/taxonomy/prepared-sync/" + StableIdentityHash.sha256(operationId) + "/" + StableIdentityHash.sha256(branch);
    }

    private record Pin(ObjectId tagId, ObjectId expected, RevCommit commit) {}

    private static Pin readPin(Repository repo, String ref, String branch) throws IOException {
        Ref stored = DslGitRepository.readFreshRef(repo, ref);
        if (stored == null) return null;
        if (stored.isSymbolic() || stored.getObjectId() == null) throw new IOException("Invalid prepared Git reference");
        try (var walk = new RevWalk(repo)) {
            var tag = walk.parseTag(stored.getObjectId());
            String expected = tag.getFullMessage().strip();
            if (!tag.getTagName().equals(branch) || !ObjectId.isId(expected) || tag.getObject().getType() != Constants.OBJ_COMMIT) {
                throw new IOException("Invalid prepared branch or candidate binding");
            }
            return new Pin(tag.copy(), ObjectId.fromString(expected), walk.parseCommit(tag.getObject()));
        }
    }

    /** The annotated tag keeps the original target and its exact commit reachable across GC and process restarts. */
    private static Pin bind(Repository repo, ObjectInserter inserter, String ref, String branch, ObjectId expected,
                            ObjectId candidate, PersonIdent actor) throws IOException {
        var tag = new TagBuilder();
        tag.setTag(branch);
        tag.setTagger(actor);
        tag.setObjectId(candidate, Constants.OBJ_COMMIT);
        tag.setMessage(expected.name());
        ObjectId tagId = inserter.insert(tag);
        inserter.flush();
        RefUpdate update = repo.updateRef(ref, true);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(tagId);
        update.setRefLogIdent(actor);
        update.setRefLogMessage("pin prepared DSL commit", false);
        var result = update.update();
        var pin = readPin(repo, ref, branch);
        if (pin == null) throw new IOException("Could not bind prepared Git candidate: " + result);
        return pin;
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
        Ref current = DslGitRepository.readFreshRef(repository, refName);
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
            String message,
            CommitProvenance provenance,
            java.util.List<String> additionalParents) {

        public CommitRequest(String branch, String expectedHeadCommit, String dslText, String author, String message, CommitProvenance provenance) {
            this(branch, expectedHeadCommit, dslText, author, message, provenance, java.util.List.of());
        }

        public CommitRequest(String branch, String expectedHeadCommit, String dslText, String author, String message) {
            this(branch, expectedHeadCommit, dslText, author, message, null);
        }

        public CommitRequest {
            branch = normalizeBranch(branch);
            if (expectedHeadCommit != null) {
                expectedHeadCommit = expectedHeadCommit.strip();
                ExpectedHeadDslCommitter.expectedHeadObjectId(
                        expectedHeadCommit);
            }
            dslText = Objects.requireNonNull(dslText, "dslText");
            author = author == null || author.isBlank()
                    ? "taxonomy"
                    : author.strip();
            message = message == null || message.isBlank()
                    ? "DSL update"
                    : message.strip();
            additionalParents = java.util.List.copyOf(additionalParents);
            if (additionalParents.size() > 1) throw new IllegalArgumentException("Only one exact incoming history is supported");
            for (String parent : additionalParents) {
                ObjectId.fromString(parent);
                if (parent.equals(expectedHeadCommit)) throw new IllegalArgumentException("Duplicate merge parent");
            }
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


}
