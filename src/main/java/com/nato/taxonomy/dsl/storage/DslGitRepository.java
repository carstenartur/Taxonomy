package com.nato.taxonomy.dsl.storage;

import com.nato.taxonomy.dsl.diff.ModelDiff;
import com.nato.taxonomy.dsl.diff.ModelDiffer;
import com.nato.taxonomy.dsl.mapper.AstToModelMapper;
import com.nato.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.nato.taxonomy.dsl.parser.TaxDslParser;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade around a JGit DFS repository that stores DSL documents as Git objects.
 *
 * <p>This is the equivalent of the {@code sandbox-jgit-storage-hibernate}
 * pattern: DSL text is stored as Git blobs, organised in trees, and
 * committed with full history — all inside the database (via DFS) rather
 * than the filesystem.
 *
 * <p>Currently backed by {@link InMemoryRepository}. When
 * {@code sandbox-jgit-storage-hibernate} is available as a Maven artifact,
 * the constructor can be changed to accept a {@code HibernateRepository}
 * instead, and all objects will be persisted in the HSQLDB
 * {@code git_packs} table.
 *
 * <pre>
 * DSL Text → JGit commit → InMemoryRepository (→ later HibernateRepository → HSQLDB)
 *                                ↕
 *              DfsObjDatabase (blobs, trees, commits)
 *              DfsRefDatabase (refs/heads/draft, refs/heads/accepted, …)
 * </pre>
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
     * Create a DslGitRepository backed by an in-memory DFS repository.
     *
     * <p>This is suitable for testing and single-JVM deployments. For
     * production with database persistence, replace with
     * {@code HibernateRepository} from {@code sandbox-jgit-storage-hibernate}.
     */
    public DslGitRepository() {
        try {
            this.gitRepo = new InMemoryRepository(new DfsRepositoryDescription("taxonomy-dsl"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create in-memory Git repository", e);
        }
        log.info("Initialised DslGitRepository (InMemoryRepository)");
    }

    /**
     * Create a DslGitRepository backed by a provided JGit repository.
     *
     * <p>Use this constructor when wiring a {@code HibernateRepository}
     * from {@code sandbox-jgit-storage-hibernate}.
     *
     * @param gitRepo the JGit repository to use (DFS or filesystem)
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
     * Compute a semantic diff between two commits.
     *
     * <p>Reads the DSL text from both commits, parses them into
     * {@link CanonicalArchitectureModel} instances, and uses
     * {@link ModelDiffer} to compute the delta.
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
}
