package com.taxonomy.dsl.storage;

import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.diff.ModelDiffer;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Taxonomy-specific facade around a JGit repository that versions one DSL document.
 *
 * <p>DSL file conventions, parsing, semantic diff and workflow operations remain
 * application concerns. Persistent pack, ref and queryable reflog storage is
 * delegated to {@code jgit-storage-hibernate-core} through its public facade.</p>
 */
public class DslGitRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DslGitRepository.class);
    private static final String DEFAULT_REPOSITORY_NAME = "taxonomy-dsl";

    /** The single DSL file stored in every commit tree. */
    static final String DSL_FILENAME = "architecture.taxdsl";

    private final Repository gitRepo;
    private final HibernateGitStorage storageHandle;
    private final boolean databaseBacked;
    private final boolean closeRepository;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final ModelDiffer differ = new ModelDiffer();

    /** Open the shared system repository through the reusable storage library. */
    public DslGitRepository(HibernateRepositoryFactory storageFactory) {
        this(storageFactory, DEFAULT_REPOSITORY_NAME);
    }

    /** Open a named logical repository through the reusable storage library. */
    public DslGitRepository(
            HibernateRepositoryFactory storageFactory,
            String repositoryName) {
        this(openStorage(storageFactory, repositoryName));
    }

    private DslGitRepository(HibernateGitStorage storageHandle) {
        this.gitRepo = storageHandle.repository();
        this.storageHandle = storageHandle;
        this.databaseBacked = true;
        this.closeRepository = false;
        log.info("Initialised database-backed DslGitRepository ({})",
                gitRepo.getClass().getSimpleName());
    }

    /** Create an owned, volatile in-memory repository for unit tests. */
    public DslGitRepository() {
        this(new InMemoryRepository(new DfsRepositoryDescription(DEFAULT_REPOSITORY_NAME)), true);
    }

    /** Use a caller-provided JGit repository without taking ownership of it. */
    public DslGitRepository(Repository gitRepo) {
        this(gitRepo, false);
    }

    DslGitRepository(Repository gitRepo, boolean closeRepository) {
        this.gitRepo = Objects.requireNonNull(gitRepo, "gitRepo");
        this.storageHandle = null;
        this.databaseBacked = false;
        this.closeRepository = closeRepository;
        log.info("Initialised DslGitRepository ({})", gitRepo.getClass().getSimpleName());
    }

    private static HibernateGitStorage openStorage(
            HibernateRepositoryFactory storageFactory,
            String repositoryName) {
        Objects.requireNonNull(storageFactory, "storageFactory");
        return storageFactory.open(new RepositoryName(repositoryName));
    }

    // ── Commit operations ───────────────────────────────────────────

    /**
     * Commit DSL text to a branch and update the branch optimistically.
     *
     * @return the new commit SHA
     * @throws IOException if object insertion or the ref update fails
     */
    public String commitDsl(String branch, String dslText, String author, String message)
            throws IOException {
        PersonIdent actor = personIdent(author);
        String commitMessage = message != null ? message : "DSL update";
        String refName = Constants.R_HEADS + branch;
        Ref branchRef = gitRepo.getRefDatabase().exactRef(refName);

        try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
            ObjectId blobId = inserter.insert(
                    Constants.OBJ_BLOB, dslText.getBytes(StandardCharsets.UTF_8));

            TreeFormatter tree = new TreeFormatter();
            tree.append(DSL_FILENAME, FileMode.REGULAR_FILE, blobId);
            ObjectId treeId = inserter.insert(tree);

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(actor);
            commit.setCommitter(actor);
            commit.setMessage(commitMessage);
            if (branchRef != null) {
                commit.setParentId(branchRef.getObjectId());
            }

            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate.Result result = updateRef(
                    refName,
                    commitId,
                    branchRef != null ? branchRef.getObjectId() : ObjectId.zeroId(),
                    false,
                    actor,
                    "commit: " + commitMessage);

            log.info("Committed DSL to branch '{}': {} (ref update: {})",
                    branch, commitId.name(), result);
            return commitId.name();
        }
    }

    // ── Read operations ─────────────────────────────────────────────

    /** Read DSL text at a specific commit. */
    public String getDslAtCommit(String commitId) throws IOException {
        ObjectId objectId = ObjectId.fromString(commitId);
        try (RevWalk walk = new RevWalk(gitRepo)) {
            return readDslFromTree(walk.parseCommit(objectId).getTree());
        }
    }

    /** Read DSL text at the HEAD of a branch. */
    public String getDslAtHead(String branch) throws IOException {
        Ref ref = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        if (ref == null) {
            return null;
        }
        return getDslAtCommit(ref.getObjectId().name());
    }

    /** Return commit history of a branch, newest first. */
    public List<DslCommit> getDslHistory(String branch) throws IOException {
        Ref ref = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        if (ref == null) {
            return List.of();
        }

        List<DslCommit> history = new ArrayList<>();
        try (RevWalk walk = new RevWalk(gitRepo)) {
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit commit : walk) {
                PersonIdent authorIdent = commit.getAuthorIdent();
                history.add(new DslCommit(
                        commit.name(),
                        authorIdent.getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        commit.getFullMessage()));
            }
        }
        return history;
    }

    // ── Branch operations ───────────────────────────────────────────

    /** List all local branches. */
    public List<DslBranch> listBranches() throws IOException {
        List<DslBranch> branches = new ArrayList<>();
        for (Ref ref : gitRepo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
            String name = ref.getName().substring(Constants.R_HEADS.length());
            String headCommitId = ref.getObjectId() != null ? ref.getObjectId().name() : null;
            Instant created = oldestCommitTime(ref);
            branches.add(new DslBranch(name, headCommitId, created));
        }
        return branches;
    }

    private Instant oldestCommitTime(Ref ref) throws IOException {
        if (ref.getObjectId() == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(gitRepo)) {
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            RevCommit oldest = null;
            for (RevCommit commit : walk) {
                oldest = commit;
            }
            return oldest != null ? Instant.ofEpochSecond(oldest.getCommitTime()) : null;
        }
    }

    /** Create a new branch at the HEAD of an existing branch. */
    public String createBranch(String newBranch, String fromBranch) throws IOException {
        Ref source = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + fromBranch);
        if (source == null) {
            return null;
        }

        String targetRef = Constants.R_HEADS + newBranch;
        updateRef(
                targetRef,
                source.getObjectId(),
                ObjectId.zeroId(),
                false,
                systemIdent(),
                "branch: created from " + fromBranch);

        log.info("Created branch '{}' from '{}' at {}",
                newBranch, fromBranch, source.getObjectId().name());
        return source.getObjectId().name();
    }

    // ── Diff operations ─────────────────────────────────────────────

    /** Compute a semantic model diff between two commits. */
    public ModelDiff diffBetween(String fromCommitId, String toCommitId) throws IOException {
        String beforeDsl = getDslAtCommit(fromCommitId);
        String afterDsl = getDslAtCommit(toCommitId);
        return differ.diff(toModel(beforeDsl), toModel(afterDsl));
    }

    /** Compute a semantic model diff between branch heads. */
    public ModelDiff diffBranches(String fromBranch, String toBranch) throws IOException {
        return differ.diff(toModel(getDslAtHead(fromBranch)), toModel(getDslAtHead(toBranch)));
    }

    private CanonicalArchitectureModel toModel(String dsl) {
        return dsl != null ? astMapper.map(parser.parse(dsl)) : null;
    }

    /** Produce a JGit-native unified text diff between two commits. */
    public String textDiff(String fromCommitId, String toCommitId) throws IOException {
        ObjectId fromObjectId = ObjectId.fromString(fromCommitId);
        ObjectId toObjectId = ObjectId.fromString(toCommitId);

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit fromCommit = walk.parseCommit(fromObjectId);
            RevCommit toCommit = walk.parseCommit(toObjectId);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(output)) {
                formatter.setRepository(gitRepo);
                formatter.format(fromCommit.getTree(), toCommit.getTree());
                formatter.flush();
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    /** Return JGit diff metadata between two commits. */
    public List<DiffEntry> jgitDiffEntries(String fromCommitId, String toCommitId)
            throws IOException {
        ObjectId fromObjectId = ObjectId.fromString(fromCommitId);
        ObjectId toObjectId = ObjectId.fromString(toCommitId);

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit fromCommit = walk.parseCommit(fromObjectId);
            RevCommit toCommit = walk.parseCommit(toObjectId);
            try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                formatter.setRepository(gitRepo);
                return formatter.scan(fromCommit.getTree(), toCommit.getTree());
            }
        }
    }

    // ── Cherry-pick and merge operations ────────────────────────────

    /** Cherry-pick a commit onto a target branch. */
    public String cherryPick(String commitId, String targetBranch) throws IOException {
        ObjectId pickObjectId = ObjectId.fromString(commitId);
        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit pickCommit = walk.parseCommit(pickObjectId);
            String targetRefName = Constants.R_HEADS + targetBranch;
            Ref targetRef = gitRepo.getRefDatabase().exactRef(targetRefName);
            if (targetRef == null) {
                log.warn("Target branch '{}' not found for cherry-pick", targetBranch);
                return null;
            }

            RevCommit targetHead = walk.parseCommit(targetRef.getObjectId());
            ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(gitRepo, true);
            if (!merger.merge(targetHead, pickCommit)) {
                log.warn("Cherry-pick merge conflict for {} onto '{}'", commitId, targetBranch);
                return null;
            }

            PersonIdent committer = systemIdent();
            try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
                CommitBuilder newCommit = new CommitBuilder();
                newCommit.setTreeId(merger.getResultTreeId());
                newCommit.setParentId(targetHead);
                newCommit.setAuthor(pickCommit.getAuthorIdent());
                newCommit.setCommitter(committer);
                newCommit.setMessage("Cherry-pick: " + pickCommit.getShortMessage());

                ObjectId newCommitId = inserter.insert(newCommit);
                inserter.flush();
                updateRef(
                        targetRefName,
                        newCommitId,
                        targetHead,
                        false,
                        committer,
                        "cherry-pick: " + pickCommit.getShortMessage());

                log.info("Cherry-picked {} onto '{}': {}",
                        commitId, targetBranch, newCommitId.name());
                return newCommitId.name();
            }
        }
    }

    /** Merge one branch into another. */
    public String merge(String fromBranch, String intoBranch) throws IOException {
        String fromRefName = Constants.R_HEADS + fromBranch;
        String intoRefName = Constants.R_HEADS + intoBranch;
        Ref fromRef = gitRepo.getRefDatabase().exactRef(fromRefName);
        Ref intoRef = gitRepo.getRefDatabase().exactRef(intoRefName);
        if (fromRef == null || intoRef == null) {
            log.warn("Cannot merge: branch not found (from={}, into={})", fromBranch, intoBranch);
            return null;
        }

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit fromCommit = walk.parseCommit(fromRef.getObjectId());
            RevCommit intoCommit = walk.parseCommit(intoRef.getObjectId());
            PersonIdent actor = systemIdent();

            if (walk.isMergedInto(fromCommit, intoCommit)) {
                log.info("Already merged: '{}' is ancestor of '{}'", fromBranch, intoBranch);
                return intoCommit.name();
            }

            if (walk.isMergedInto(intoCommit, fromCommit)) {
                updateRef(
                        intoRefName,
                        fromCommit,
                        intoCommit,
                        false,
                        actor,
                        "merge: fast-forward " + fromBranch + " into " + intoBranch);
                log.info("Fast-forward merge '{}' into '{}': {}",
                        fromBranch, intoBranch, fromCommit.name());
                return fromCommit.name();
            }

            ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(gitRepo, true);
            if (!merger.merge(intoCommit, fromCommit)) {
                log.warn("Merge conflict: '{}' into '{}'", fromBranch, intoBranch);
                return null;
            }

            try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
                CommitBuilder mergeCommit = new CommitBuilder();
                mergeCommit.setTreeId(merger.getResultTreeId());
                mergeCommit.setParentIds(intoCommit, fromCommit);
                mergeCommit.setAuthor(actor);
                mergeCommit.setCommitter(actor);
                mergeCommit.setMessage("Merge branch '" + fromBranch + "' into " + intoBranch);

                ObjectId mergeCommitId = inserter.insert(mergeCommit);
                inserter.flush();
                updateRef(
                        intoRefName,
                        mergeCommitId,
                        intoCommit,
                        false,
                        actor,
                        "merge: " + fromBranch + " into " + intoBranch);

                log.info("Merged '{}' into '{}': {}",
                        fromBranch, intoBranch, mergeCommitId.name());
                return mergeCommitId.name();
            }
        }
    }

    // ── Revert and undo operations ──────────────────────────────────

    /** Revert a specific commit on a branch by creating a new commit. */
    public String revert(String commitId, String branch) throws IOException {
        ObjectId revertObjectId = ObjectId.fromString(commitId);
        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit revertCommit = walk.parseCommit(revertObjectId);
            if (revertCommit.getParentCount() == 0) {
                log.warn("Cannot revert initial commit {}", commitId);
                return null;
            }

            String refName = Constants.R_HEADS + branch;
            Ref branchRef = gitRepo.getRefDatabase().exactRef(refName);
            if (branchRef == null) {
                log.warn("Branch '{}' not found for revert", branch);
                return null;
            }

            RevCommit branchHead = walk.parseCommit(branchRef.getObjectId());
            RevCommit parentCommit = walk.parseCommit(revertCommit.getParent(0));
            ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(gitRepo, true);
            merger.setBase(revertCommit);
            if (!merger.merge(branchHead, parentCommit)) {
                log.warn("Revert conflict for {} on '{}'", commitId, branch);
                return null;
            }

            PersonIdent actor = systemIdent();
            try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
                CommitBuilder newCommit = new CommitBuilder();
                newCommit.setTreeId(merger.getResultTreeId());
                newCommit.setParentId(branchHead);
                newCommit.setAuthor(actor);
                newCommit.setCommitter(actor);
                newCommit.setMessage("Revert: " + revertCommit.getShortMessage());

                ObjectId newCommitId = inserter.insert(newCommit);
                inserter.flush();
                updateRef(
                        refName,
                        newCommitId,
                        branchHead,
                        false,
                        actor,
                        "revert: " + revertCommit.getShortMessage());

                log.info("Reverted {} on '{}': {}", commitId, branch, newCommitId.name());
                return newCommitId.name();
            }
        }
    }

    /** Move a branch back to its first parent. */
    public String undoLast(String branch) throws IOException {
        String refName = Constants.R_HEADS + branch;
        Ref branchRef = gitRepo.getRefDatabase().exactRef(refName);
        if (branchRef == null) {
            log.warn("Branch '{}' not found for undo", branch);
            return null;
        }

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit headCommit = walk.parseCommit(branchRef.getObjectId());
            if (headCommit.getParentCount() == 0) {
                log.warn("Cannot undo: branch '{}' has only the initial commit", branch);
                return null;
            }

            RevCommit parentCommit = walk.parseCommit(headCommit.getParent(0));
            updateRef(
                    refName,
                    parentCommit,
                    headCommit,
                    true,
                    systemIdent(),
                    "reset: moving to " + parentCommit.name());

            log.info("Undo last on '{}': {} -> {}",
                    branch, headCommit.name(), parentCommit.name());
            return parentCommit.name();
        }
    }

    /** Restore DSL content from a historic commit as a new branch commit. */
    public String restore(String commitId, String branch) throws IOException {
        String dslText;
        try {
            dslText = getDslAtCommit(commitId);
        } catch (org.eclipse.jgit.errors.MissingObjectException exception) {
            log.warn("Cannot restore: commit {} not found", commitId);
            return null;
        }
        if (dslText == null) {
            log.warn("Cannot restore: commit {} has no DSL content", commitId);
            return null;
        }
        return commitDsl(
                branch,
                dslText,
                "taxonomy",
                "Restored from version " + commitId.substring(0, 7));
    }

    // ── State query helpers ─────────────────────────────────────────

    /** Return the HEAD SHA for a branch, or {@code null}. */
    public String getHeadCommit(String branch) throws IOException {
        Ref ref = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        return ref != null && ref.getObjectId() != null ? ref.getObjectId().name() : null;
    }

    /** Return all branch names without the {@code refs/heads/} prefix. */
    public List<String> getBranchNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (Ref ref : gitRepo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
            names.add(ref.getName().substring(Constants.R_HEADS.length()));
        }
        return names;
    }

    /** Count commits reachable from a branch. */
    public int getCommitCount(String branch) throws IOException {
        Ref ref = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        if (ref == null) {
            return 0;
        }

        int count = 0;
        try (RevWalk walk = new RevWalk(gitRepo)) {
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit ignored : walk) {
                count++;
            }
        }
        return count;
    }

    /** Return {@code [ahead, behind]} commit counts for two branches. */
    public int[] getAheadBehindCounts(String branch, String baseBranch) throws IOException {
        Ref branchRef = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        Ref baseRef = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + baseBranch);
        if (branchRef == null || baseRef == null) {
            return new int[]{0, 0};
        }

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit branchHead = walk.parseCommit(branchRef.getObjectId());
            RevCommit baseHead = walk.parseCommit(baseRef.getObjectId());
            if (branchHead.equals(baseHead)) {
                return new int[]{0, 0};
            }

            int ahead = countCommitsNotIn(walk, branchHead, baseHead);
            int behind = countCommitsNotIn(walk, baseHead, branchHead);
            return new int[]{ahead, behind};
        }
    }

    private static int countCommitsNotIn(
            RevWalk walk,
            RevCommit tip,
            RevCommit exclude) throws IOException {
        walk.reset();
        walk.markStart(tip);
        walk.markUninteresting(exclude);
        int count = 0;
        for (RevCommit ignored : walk) {
            count++;
        }
        return count;
    }

    /** Return metadata for the HEAD commit of a branch. */
    public DslCommit getHeadCommitInfo(String branch) throws IOException {
        Ref ref = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        if (ref == null) {
            return null;
        }

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit commit = walk.parseCommit(ref.getObjectId());
            PersonIdent authorIdent = commit.getAuthorIdent();
            return new DslCommit(
                    commit.name(),
                    authorIdent.getName(),
                    Instant.ofEpochSecond(commit.getCommitTime()),
                    commit.getFullMessage());
        }
    }

    private String readDslFromTree(RevTree tree) throws IOException {
        try (TreeWalk treeWalk = TreeWalk.forPath(gitRepo, DSL_FILENAME, tree)) {
            if (treeWalk == null) {
                return null;
            }
            ObjectLoader loader = gitRepo.open(treeWalk.getObjectId(0), Constants.OBJ_BLOB);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }

    private RefUpdate.Result updateRef(
            String refName,
            ObjectId newObjectId,
            ObjectId expectedOldObjectId,
            boolean force,
            PersonIdent actor,
            String reflogMessage) throws IOException {
        RefUpdate update = gitRepo.updateRef(refName);
        update.setNewObjectId(newObjectId);
        update.setExpectedOldObjectId(expectedOldObjectId);
        update.setForceUpdate(force);
        update.setRefLogIdent(actor);
        update.setRefLogMessage(reflogMessage, true);
        RefUpdate.Result result = update.update();
        requireSuccessfulUpdate(refName, result);
        return result;
    }

    private static void requireSuccessfulUpdate(String refName, RefUpdate.Result result)
            throws IOException {
        if (result != RefUpdate.Result.NEW
                && result != RefUpdate.Result.FAST_FORWARD
                && result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("Ref update failed for " + refName + ": " + result);
        }
    }

    private static PersonIdent personIdent(String author) {
        String name = author != null ? author : "taxonomy";
        String email = author != null ? author : "taxonomy@system";
        return new PersonIdent(name, email);
    }

    private static PersonIdent systemIdent() {
        return new PersonIdent("taxonomy", "taxonomy@system");
    }

    /** Expose the underlying public JGit repository for advanced operations. */
    public Repository getGitRepository() {
        return gitRepo;
    }

    /** Return whether this instance uses persistent Hibernate-backed storage. */
    public boolean isDatabaseBacked() {
        return databaseBacked;
    }

    /** Delete a non-protected branch and record a queryable reflog entry. */
    public boolean deleteBranch(String branchName) throws IOException {
        if ("draft".equals(branchName)
                || "accepted".equals(branchName)
                || "main".equals(branchName)) {
            throw new IllegalArgumentException("Cannot delete protected branch: " + branchName);
        }

        String refName = Constants.R_HEADS + branchName;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) {
            return false;
        }

        RefUpdate update = gitRepo.updateRef(refName);
        update.setExpectedOldObjectId(ref.getObjectId());
        update.setForceUpdate(true);
        update.setRefLogIdent(systemIdent());
        update.setRefLogMessage("branch: deleted " + branchName, true);
        RefUpdate.Result result = update.delete();
        if (result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.FAST_FORWARD) {
            throw new IOException("Ref deletion failed for " + refName + ": " + result);
        }

        log.info("Deleted branch '{}' (ref update: {})", branchName, result);
        return true;
    }

    /** Close the library handle or an owned in-memory repository exactly once. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (storageHandle != null) {
            storageHandle.close();
        } else if (closeRepository) {
            gitRepo.close();
        }
    }
}
