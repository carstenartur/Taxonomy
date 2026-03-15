package com.taxonomy.dsl.storage;

import com.taxonomy.dsl.diff.ModelDiff;
import com.taxonomy.dsl.diff.ModelDiffer;
import com.taxonomy.dsl.mapper.AstToModelMapper;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.dsl.storage.jgit.HibernateRepository;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.Merger;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade around a JGit DFS repository that stores DSL documents as Git objects.
 *
 * <p>Implements the {@code sandbox-jgit-storage-hibernate} pattern: DSL text is
 * stored as Git blobs, organised in trees, and committed with full history — all
 * inside the database (via {@link HibernateRepository}) rather than the filesystem.
 *
 * <pre>
 * DSL Text → JGit commit → HibernateRepository → HSQLDB (git_packs table)
 *                                ↕
 *              HibernateObjDatabase (blobs, trees, commits as pack data)
 *              HibernateRefDatabase (reftable stored as pack extension)
 * </pre>
 *
 * <p>All Git objects (blobs, trees, commits) and refs are persisted in the
 * {@code git_packs} and {@code git_reflog} database tables. No filesystem is
 * used; everything lives in the existing HSQLDB instance.
 *
 * <p>For tests, a no-arg constructor creates an {@link InMemoryRepository} as
 * a volatile fallback.
 */
public class DslGitRepository {

    private static final Logger log = LoggerFactory.getLogger(DslGitRepository.class);

    /** The single DSL file stored in every commit tree. */
    static final String DSL_FILENAME = "architecture.taxdsl";

    private final Repository gitRepo;
    private final TaxDslParser parser = new TaxDslParser();
    private final AstToModelMapper astMapper = new AstToModelMapper();
    private final ModelDiffer differ = new ModelDiffer();

    /**
     * Create a DslGitRepository backed by a database via Hibernate.
     *
     * <p>This is the primary constructor for production use. All Git objects
     * are stored in the {@code git_packs} table, refs in reftable format
     * persisted as pack extensions — exactly as the
     * {@code sandbox-jgit-storage-hibernate} module does.
     *
     * @param sessionFactory the Hibernate SessionFactory (shared with the
     *                       existing HSQLDB in the Spring Boot app)
     */
    public DslGitRepository(SessionFactory sessionFactory) {
        this.gitRepo = new HibernateRepository(sessionFactory, "taxonomy-dsl");
        log.info("Initialised DslGitRepository (HibernateRepository → database)");
    }

    /**
     * Create a DslGitRepository backed by an in-memory DFS repository.
     *
     * <p>This is suitable for testing only. Data is lost on JVM restart.
     */
    public DslGitRepository() {
        this.gitRepo = new InMemoryRepository(new DfsRepositoryDescription("taxonomy-dsl"));
        log.info("Initialised DslGitRepository (InMemoryRepository — volatile)");
    }

    /**
     * Create a DslGitRepository backed by a provided JGit repository.
     *
     * @param gitRepo the JGit repository to use (any DFS implementation)
     */
    public DslGitRepository(Repository gitRepo) {
        this.gitRepo = gitRepo;
        log.info("Initialised DslGitRepository ({})", gitRepo.getClass().getSimpleName());
    }

    // ── Commit operations ───────────────────────────────────────────

    /**
     * Commit DSL text to the specified branch.
     *
     * <p>This creates a Git blob (DSL text), a tree ({@value DSL_FILENAME} → blob),
     * and a commit object with the given author and message. The branch ref
     * is updated to point at the new commit.
     *
     * @param branch  branch name (e.g. "draft", "review", "accepted")
     * @param dslText the DSL document content
     * @param author  author identity string (e.g. "user@example.com")
     * @param message commit message
     * @return the commit SHA hex string
     * @throws IOException on JGit errors
     */
    public String commitDsl(String branch, String dslText, String author, String message)
            throws IOException {

        PersonIdent personIdent = new PersonIdent(
                author != null ? author : "taxonomy",
                author != null ? author : "taxonomy@system");

        try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
            // 1. DSL text → Blob
            ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
                    dslText.getBytes(StandardCharsets.UTF_8));

            // 2. Tree (architecture.taxdsl → blobId)
            TreeFormatter tree = new TreeFormatter();
            tree.append(DSL_FILENAME, FileMode.REGULAR_FILE, blobId);
            ObjectId treeId = inserter.insert(tree);

            // 3. Commit
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(personIdent);
            commit.setCommitter(personIdent);
            commit.setMessage(message != null ? message : "DSL update");

            // Parent = current HEAD of the branch
            String refName = Constants.R_HEADS + branch;
            Ref branchRef = gitRepo.getRefDatabase().exactRef(refName);
            if (branchRef != null) {
                commit.setParentId(branchRef.getObjectId());
            }

            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            // 4. Update branch ref
            RefUpdate ru = gitRepo.updateRef(refName);
            ru.setNewObjectId(commitId);
            if (branchRef == null) {
                ru.setExpectedOldObjectId(ObjectId.zeroId());
            }
            ru.setForceUpdate(true);
            RefUpdate.Result result = ru.update();

            log.info("Committed DSL to branch '{}': {} (ref update: {})",
                    branch, commitId.name(), result);

            return commitId.name();
        }
    }

    // ── Read operations ─────────────────────────────────────────────

    /**
     * Read the DSL text at a specific commit.
     *
     * @param commitId the commit SHA hex string
     * @return the DSL text, or {@code null} if the file is not found
     * @throws IOException on JGit errors
     */
    public String getDslAtCommit(String commitId) throws IOException {
        ObjectId oid = ObjectId.fromString(commitId);
        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit revCommit = walk.parseCommit(oid);
            return readDslFromTree(revCommit.getTree());
        }
    }

    /**
     * Read the DSL text at the HEAD of a branch.
     *
     * @param branch the branch name
     * @return the DSL text, or {@code null} if the branch has no commits
     * @throws IOException on JGit errors
     */
    public String getDslAtHead(String branch) throws IOException {
        String refName = Constants.R_HEADS + branch;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) return null;
        return getDslAtCommit(ref.getObjectId().name());
    }

    /**
     * Get the commit history of a branch, newest first.
     *
     * @param branch the branch name
     * @return the list of commits (may be empty)
     * @throws IOException on JGit errors
     */
    public List<DslCommit> getDslHistory(String branch) throws IOException {
        String refName = Constants.R_HEADS + branch;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) return List.of();

        List<DslCommit> history = new ArrayList<>();
        try (RevWalk walk = new RevWalk(gitRepo)) {
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit c : walk) {
                PersonIdent authorIdent = c.getAuthorIdent();
                history.add(new DslCommit(
                        c.name(),
                        authorIdent.getName(),
                        Instant.ofEpochSecond(c.getCommitTime()),
                        c.getFullMessage()));
            }
        }
        return history;
    }

    // ── Branch operations ───────────────────────────────────────────

    /**
     * List all branches.
     *
     * @return list of branch DTOs
     * @throws IOException on JGit errors
     */
    public List<DslBranch> listBranches() throws IOException {
        List<DslBranch> branches = new ArrayList<>();
        for (Ref ref : gitRepo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
            String name = ref.getName().substring(Constants.R_HEADS.length());
            String headCommitId = ref.getObjectId() != null ? ref.getObjectId().name() : null;

            Instant created = null;
            if (headCommitId != null) {
                try (RevWalk walk = new RevWalk(gitRepo)) {
                    walk.markStart(walk.parseCommit(ref.getObjectId()));
                    RevCommit oldest = null;
                    for (RevCommit c : walk) { oldest = c; }
                    if (oldest != null) {
                        created = Instant.ofEpochSecond(oldest.getCommitTime());
                    }
                }
            }
            branches.add(new DslBranch(name, headCommitId, created));
        }
        return branches;
    }

    /**
     * Create a new branch pointing at the HEAD of an existing branch.
     *
     * @param newBranch  the new branch name
     * @param fromBranch the source branch to fork from
     * @return the head commit ID of the new branch, or {@code null} if source is empty
     * @throws IOException on JGit errors
     */
    public String createBranch(String newBranch, String fromBranch) throws IOException {
        String sourceRef = Constants.R_HEADS + fromBranch;
        Ref ref = gitRepo.getRefDatabase().exactRef(sourceRef);
        if (ref == null) return null;

        String targetRef = Constants.R_HEADS + newBranch;
        RefUpdate ru = gitRepo.updateRef(targetRef);
        ru.setNewObjectId(ref.getObjectId());
        ru.setExpectedOldObjectId(ObjectId.zeroId());
        ru.setForceUpdate(true);
        ru.update();

        log.info("Created branch '{}' from '{}' at {}", newBranch, fromBranch,
                ref.getObjectId().name());
        return ref.getObjectId().name();
    }

    // ── Diff operations ─────────────────────────────────────────────

    /**
     * Compute a semantic diff between two commits using {@link ModelDiffer}.
     *
     * <p>Reads the DSL text from both commits, parses them into
     * {@link CanonicalArchitectureModel} instances, and computes the delta.
     *
     * @param fromCommitId the "before" commit SHA
     * @param toCommitId   the "after" commit SHA
     * @return the model diff
     * @throws IOException on JGit errors
     */
    public ModelDiff diffBetween(String fromCommitId, String toCommitId) throws IOException {
        String beforeDsl = getDslAtCommit(fromCommitId);
        String afterDsl = getDslAtCommit(toCommitId);

        CanonicalArchitectureModel beforeModel = beforeDsl != null
                ? astMapper.map(parser.parse(beforeDsl)) : null;
        CanonicalArchitectureModel afterModel = afterDsl != null
                ? astMapper.map(parser.parse(afterDsl)) : null;

        return differ.diff(beforeModel, afterModel);
    }

    /**
     * Compute a semantic diff between the HEAD of two branches.
     *
     * @param fromBranch the "before" branch
     * @param toBranch   the "after" branch
     * @return the model diff
     * @throws IOException on JGit errors
     */
    public ModelDiff diffBranches(String fromBranch, String toBranch) throws IOException {
        String beforeDsl = getDslAtHead(fromBranch);
        String afterDsl = getDslAtHead(toBranch);

        CanonicalArchitectureModel beforeModel = beforeDsl != null
                ? astMapper.map(parser.parse(beforeDsl)) : null;
        CanonicalArchitectureModel afterModel = afterDsl != null
                ? astMapper.map(parser.parse(afterDsl)) : null;

        return differ.diff(beforeModel, afterModel);
    }

    /**
     * Compute a JGit-native text diff between two commits.
     *
     * <p>Uses JGit's {@link DiffFormatter} to produce a unified-diff patch
     * of the DSL file changes, plus a list of {@link DiffEntry} metadata.
     * This is what a real Git diff would produce.
     *
     * @param fromCommitId the "before" commit SHA
     * @param toCommitId   the "after" commit SHA
     * @return the unified diff as a string
     * @throws IOException on JGit errors
     */
    public String textDiff(String fromCommitId, String toCommitId) throws IOException {
        ObjectId fromOid = ObjectId.fromString(fromCommitId);
        ObjectId toOid = ObjectId.fromString(toCommitId);

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit fromCommit = walk.parseCommit(fromOid);
            RevCommit toCommit = walk.parseCommit(toOid);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DiffFormatter df = new DiffFormatter(out)) {
                df.setRepository(gitRepo);
                df.format(fromCommit.getTree(), toCommit.getTree());
                df.flush();
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Get the list of changed files between two commits using JGit DiffFormatter.
     *
     * @param fromCommitId the "before" commit SHA
     * @param toCommitId   the "after" commit SHA
     * @return list of DiffEntry objects describing what changed
     * @throws IOException on JGit errors
     */
    public List<DiffEntry> jgitDiffEntries(String fromCommitId, String toCommitId) throws IOException {
        ObjectId fromOid = ObjectId.fromString(fromCommitId);
        ObjectId toOid = ObjectId.fromString(toCommitId);

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit fromCommit = walk.parseCommit(fromOid);
            RevCommit toCommit = walk.parseCommit(toOid);

            try (DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(gitRepo);
                return df.scan(fromCommit.getTree(), toCommit.getTree());
            }
        }
    }

    // ── Cherry-pick & merge operations ──────────────────────────────

    /**
     * Cherry-pick a commit onto a target branch.
     *
     * <p>Applies the changes from the specified commit to the HEAD of the
     * target branch, creating a new commit.
     *
     * @param commitId     the commit to cherry-pick
     * @param targetBranch the branch to apply the commit to
     * @return the new commit SHA, or {@code null} if cherry-pick failed
     * @throws IOException on JGit errors
     */
    public String cherryPick(String commitId, String targetBranch) throws IOException {
        ObjectId pickOid = ObjectId.fromString(commitId);

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit pickCommit = walk.parseCommit(pickOid);

            String targetRefName = Constants.R_HEADS + targetBranch;
            Ref targetRef = gitRepo.getRefDatabase().exactRef(targetRefName);

            if (targetRef == null) {
                log.warn("Target branch '{}' not found for cherry-pick", targetBranch);
                return null;
            }

            RevCommit targetHead = walk.parseCommit(targetRef.getObjectId());

            // Three-way merge: base=parent (or auto-detected for initial commit),
            // ours=targetHead, theirs=pickCommit
            ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(gitRepo, true);
            boolean success = merger.merge(targetHead, pickCommit);

            if (!success) {
                log.warn("Cherry-pick merge conflict for {} onto '{}'", commitId, targetBranch);
                return null;
            }

            // Create the new commit on the target branch
            try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
                CommitBuilder newCommit = new CommitBuilder();
                newCommit.setTreeId(merger.getResultTreeId());
                newCommit.setParentId(targetHead);
                newCommit.setAuthor(pickCommit.getAuthorIdent());
                newCommit.setCommitter(new PersonIdent("taxonomy", "taxonomy@system"));
                newCommit.setMessage("Cherry-pick: " + pickCommit.getShortMessage());

                ObjectId newCommitId = inserter.insert(newCommit);
                inserter.flush();

                // Update target branch ref
                RefUpdate ru = gitRepo.updateRef(targetRefName);
                ru.setNewObjectId(newCommitId);
                ru.setForceUpdate(true);
                ru.update();

                log.info("Cherry-picked {} onto '{}': {}", commitId, targetBranch, newCommitId.name());
                return newCommitId.name();
            }
        }
    }

    /**
     * Merge one branch into another.
     *
     * <p>Performs a three-way merge of {@code fromBranch} into {@code intoBranch}.
     * On success, creates a merge commit on the target branch with two parents.
     *
     * @param fromBranch the branch to merge from (source)
     * @param intoBranch the branch to merge into (target)
     * @return the merge commit SHA, or {@code null} if merge failed
     * @throws IOException on JGit errors
     */
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

            // Fast-forward check
            if (walk.isMergedInto(fromCommit, intoCommit)) {
                log.info("Already merged: '{}' is ancestor of '{}'", fromBranch, intoBranch);
                return intoCommit.name();
            }

            // Can we fast-forward?
            if (walk.isMergedInto(intoCommit, fromCommit)) {
                // Fast-forward: just move the target ref
                RefUpdate ru = gitRepo.updateRef(intoRefName);
                ru.setNewObjectId(fromCommit);
                ru.setForceUpdate(true);
                ru.update();
                log.info("Fast-forward merge '{}' into '{}': {}", fromBranch, intoBranch, fromCommit.name());
                return fromCommit.name();
            }

            // Three-way merge
            ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(gitRepo, true);
            boolean success = merger.merge(intoCommit, fromCommit);

            if (!success) {
                log.warn("Merge conflict: '{}' into '{}'", fromBranch, intoBranch);
                return null;
            }

            // Create merge commit with two parents
            try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
                CommitBuilder mergeCommit = new CommitBuilder();
                mergeCommit.setTreeId(merger.getResultTreeId());
                mergeCommit.setParentIds(intoCommit, fromCommit);
                PersonIdent ident = new PersonIdent("taxonomy", "taxonomy@system");
                mergeCommit.setAuthor(ident);
                mergeCommit.setCommitter(ident);
                mergeCommit.setMessage("Merge branch '" + fromBranch + "' into " + intoBranch);

                ObjectId mergeCommitId = inserter.insert(mergeCommit);
                inserter.flush();

                RefUpdate ru = gitRepo.updateRef(intoRefName);
                ru.setNewObjectId(mergeCommitId);
                ru.setForceUpdate(true);
                ru.update();

                log.info("Merged '{}' into '{}': {}", fromBranch, intoBranch, mergeCommitId.name());
                return mergeCommitId.name();
            }
        }
    }

    // ── Revert & undo operations ───────────────────────────────────

    /**
     * Revert a specific commit on a branch.
     *
     * <p>Creates a new commit that undoes the changes introduced by the
     * specified commit. Uses a three-way merge where the base is the commit
     * to revert, ours is the branch HEAD, and theirs is the parent of the
     * commit to revert (i.e. the state before the commit).
     *
     * @param commitId the commit SHA to revert
     * @param branch   the branch on which to create the revert commit
     * @return the new revert commit SHA, or {@code null} if the revert failed
     * @throws IOException on JGit errors
     */
    public String revert(String commitId, String branch) throws IOException {
        ObjectId revertOid = ObjectId.fromString(commitId);

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit revertCommit = walk.parseCommit(revertOid);

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

            // Three-way merge: base=revertCommit, ours=branchHead, theirs=parentCommit
            // This effectively applies the inverse of revertCommit's changes
            ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(gitRepo, true);
            merger.setBase(revertCommit);
            boolean success = merger.merge(branchHead, parentCommit);

            if (!success) {
                log.warn("Revert conflict for {} on '{}'", commitId, branch);
                return null;
            }

            try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
                CommitBuilder newCommit = new CommitBuilder();
                newCommit.setTreeId(merger.getResultTreeId());
                newCommit.setParentId(branchHead);
                PersonIdent ident = new PersonIdent("taxonomy", "taxonomy@system");
                newCommit.setAuthor(ident);
                newCommit.setCommitter(ident);
                newCommit.setMessage("Revert: " + revertCommit.getShortMessage());

                ObjectId newCommitId = inserter.insert(newCommit);
                inserter.flush();

                RefUpdate ru = gitRepo.updateRef(refName);
                ru.setNewObjectId(newCommitId);
                ru.setForceUpdate(true);
                ru.update();

                log.info("Reverted {} on '{}': {}", commitId, branch, newCommitId.name());
                return newCommitId.name();
            }
        }
    }

    /**
     * Undo the last commit on a branch by resetting to its parent.
     *
     * <p>This is a simple "soft reset" — the branch ref is moved to the parent
     * commit, effectively removing the last commit from the branch history.
     * The commit object still exists in the repository but is no longer
     * reachable from the branch.
     *
     * @param branch the branch name
     * @return the new HEAD SHA (the parent), or {@code null} if no parent exists
     * @throws IOException on JGit errors
     */
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

            RefUpdate ru = gitRepo.updateRef(refName);
            ru.setNewObjectId(parentCommit);
            ru.setForceUpdate(true);
            ru.update();

            log.info("Undo last on '{}': {} → {}", branch, headCommit.name(), parentCommit.name());
            return parentCommit.name();
        }
    }

    /**
     * Restore the DSL content from a specific commit as a new commit on the branch.
     *
     * <p>Reads the DSL text from the given commit and creates a new commit on
     * the specified branch with that content. This is a "restore to version"
     * operation — the branch moves forward with a new commit whose tree
     * matches the old commit.
     *
     * @param commitId the source commit SHA to restore from
     * @param branch   the branch to create the new commit on
     * @return the new commit SHA, or {@code null} if the source commit was not found
     * @throws IOException on JGit errors
     */
    public String restore(String commitId, String branch) throws IOException {
        String dslText;
        try {
            dslText = getDslAtCommit(commitId);
        } catch (org.eclipse.jgit.errors.MissingObjectException e) {
            log.warn("Cannot restore: commit {} not found", commitId);
            return null;
        }
        if (dslText == null) {
            log.warn("Cannot restore: commit {} has no DSL content", commitId);
            return null;
        }

        return commitDsl(branch, dslText, "taxonomy",
                "Restored from version " + commitId.substring(0, 7));
    }

    // ── State query helpers ────────────────────────────────────────

    /**
     * Get the HEAD commit SHA for a branch.
     *
     * @param branch the branch name
     * @return the 40-char commit SHA, or {@code null} if the branch doesn't exist
     * @throws IOException on JGit errors
     */
    public String getHeadCommit(String branch) throws IOException {
        String refName = Constants.R_HEADS + branch;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        return ref != null && ref.getObjectId() != null ? ref.getObjectId().name() : null;
    }

    /**
     * Get all branch names (without the {@code refs/heads/} prefix).
     *
     * @return list of branch names (may be empty)
     * @throws IOException on JGit errors
     */
    public List<String> getBranchNames() throws IOException {
        List<String> names = new ArrayList<>();
        for (Ref ref : gitRepo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
            names.add(ref.getName().substring(Constants.R_HEADS.length()));
        }
        return names;
    }

    /**
     * Count the number of commits on a branch.
     *
     * @param branch the branch name
     * @return the commit count (0 if branch doesn't exist)
     * @throws IOException on JGit errors
     */
    public int getCommitCount(String branch) throws IOException {
        String refName = Constants.R_HEADS + branch;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) return 0;

        int count = 0;
        try (RevWalk walk = new RevWalk(gitRepo)) {
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit ignored : walk) {
                count++;
            }
        }
        return count;
    }

    /**
     * Compute ahead/behind counts between two branches using merge-base.
     *
     * <p>Returns a two-element array: {@code [ahead, behind]} where:
     * <ul>
     *   <li>{@code ahead} = number of commits in {@code branch} not in {@code baseBranch}</li>
     *   <li>{@code behind} = number of commits in {@code baseBranch} not in {@code branch}</li>
     * </ul>
     *
     * <p>This uses JGit's {@code RevWalk.isMergedInto()} and commit walking
     * relative to the merge-base to produce accurate counts, unlike the simple
     * total-commit-count heuristic.
     *
     * @param branch     the branch to compare (e.g. user's branch)
     * @param baseBranch the reference branch (e.g. shared "draft")
     * @return {@code int[]{ahead, behind}}, or {@code int[]{0, 0}} if either branch is missing
     * @throws IOException on JGit errors
     */
    public int[] getAheadBehindCounts(String branch, String baseBranch) throws IOException {
        Ref branchRef = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        Ref baseRef = gitRepo.getRefDatabase().exactRef(Constants.R_HEADS + baseBranch);

        if (branchRef == null || baseRef == null) {
            return new int[]{0, 0};
        }

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit branchHead = walk.parseCommit(branchRef.getObjectId());
            RevCommit baseHead = walk.parseCommit(baseRef.getObjectId());

            // If same commit, no divergence
            if (branchHead.equals(baseHead)) {
                return new int[]{0, 0};
            }

            // Count commits reachable from branch but not from base (ahead)
            int ahead = countCommitsNotIn(walk, branchHead, baseHead);

            // Reset walk for the reverse count
            walk.reset();

            // Count commits reachable from base but not from branch (behind)
            int behind = countCommitsNotIn(walk, baseHead, branchHead);

            return new int[]{ahead, behind};
        }
    }

    /**
     * Count commits reachable from {@code tip} but not from {@code exclude}.
     */
    private int countCommitsNotIn(RevWalk walk, RevCommit tip, RevCommit exclude)
            throws IOException {
        walk.reset();
        walk.markStart(tip);
        walk.markUninteresting(exclude);
        int count = 0;
        for (RevCommit ignored : walk) {
            count++;
        }
        return count;
    }

    /**
     * Get the {@link DslCommit} metadata for the HEAD of a branch.
     *
     * @param branch the branch name
     * @return the HEAD commit info, or {@code null} if the branch has no commits
     * @throws IOException on JGit errors
     */
    public DslCommit getHeadCommitInfo(String branch) throws IOException {
        String refName = Constants.R_HEADS + branch;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) return null;

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit c = walk.parseCommit(ref.getObjectId());
            PersonIdent authorIdent = c.getAuthorIdent();
            return new DslCommit(
                    c.name(),
                    authorIdent.getName(),
                    Instant.ofEpochSecond(c.getCommitTime()),
                    c.getFullMessage());
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    private String readDslFromTree(RevTree tree) throws IOException {
        try (TreeWalk tw = TreeWalk.forPath(gitRepo, DSL_FILENAME, tree)) {
            if (tw == null) return null;
            ObjectId blobId = tw.getObjectId(0);
            ObjectLoader loader = gitRepo.open(blobId, Constants.OBJ_BLOB);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Expose the underlying JGit repository (for advanced operations). */
    public Repository getGitRepository() {
        return gitRepo;
    }

    /**
     * Check if this repository is backed by a database (Hibernate) or in-memory.
     *
     * @return true if backed by HibernateRepository (persistent)
     */
    public boolean isDatabaseBacked() {
        return gitRepo instanceof HibernateRepository;
    }
}
