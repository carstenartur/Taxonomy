package com.taxonomy.preferences.storage;

import com.taxonomy.dsl.storage.jgit.HibernateRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import com.taxonomy.dsl.storage.jgit.HibernateObjDatabase;
import com.taxonomy.dsl.storage.jgit.HibernateRefDatabase;

/**
 * Facade around a JGit DFS repository that stores preferences as a JSON file.
 *
 * <p>Uses the {@code sandbox-jgit-storage-hibernate} pattern with a <em>separate</em>
 * project name ({@code "taxonomy-preferences"}) so that preferences history is
 * completely decoupled from the Architecture DSL history ({@code "taxonomy-dsl"}).
 *
 * <pre>
 * preferences.json → JGit commit → HibernateRepository("taxonomy-preferences")
 *                                         ↕
 *                   HibernateObjDatabase / HibernateRefDatabase (git_packs table)
 * </pre>
 *
 * <p>Both repositories share the same {@code git_packs} database table but are
 * logically independent Git repositories with separate commit histories, branches,
 * and refs.
 *
 * <p>For tests, the no-arg constructor creates an {@link InMemoryRepository}.
 */
public class PreferencesGitRepository {

    private static final Logger log = LoggerFactory.getLogger(PreferencesGitRepository.class);

    /** The single JSON file stored in every commit tree. */
    static final String PREFERENCES_FILENAME = "preferences.json";

    /** The single branch used for preferences history. */
    static final String MAIN_BRANCH = "main";

    private final Repository gitRepo;

    /**
     * Create a PreferencesGitRepository backed by a database via Hibernate.
     *
     * <p>This is the primary constructor for production use. All Git objects
     * are stored in the {@code git_packs} table — exactly as the
     * {@code sandbox-jgit-storage-hibernate} module does, but in a
     * <strong>separate</strong> project ({@code "taxonomy-preferences"}) from
     * the Architecture DSL repository.
     *
     * @param sessionFactory the Hibernate SessionFactory (shared with the existing app)
     */
    public PreferencesGitRepository(SessionFactory sessionFactory) {
        this.gitRepo = new HibernateRepository(sessionFactory, "taxonomy-preferences");
        log.info("Initialised PreferencesGitRepository (HibernateRepository → database)");
    }

    /**
     * Create a PreferencesGitRepository backed by an in-memory DFS repository.
     *
     * <p>This is suitable for testing only. Data is lost on JVM restart.
     */
    public PreferencesGitRepository() {
        this.gitRepo = new InMemoryRepository(
                new DfsRepositoryDescription("taxonomy-preferences"));
        log.info("Initialised PreferencesGitRepository (InMemoryRepository — volatile)");
    }

    /**
     * Commit preferences JSON to the {@code main} branch.
     *
     * @param jsonContent the preferences JSON string
     * @param author      author identity string (e.g. username)
     * @param message     commit message
     * @return the commit SHA hex string
     * @throws IOException on JGit errors
     */
    public String commit(String jsonContent, String author, String message) throws IOException {
        PersonIdent personIdent = new PersonIdent(
                author != null ? author : "taxonomy",
                author != null ? author : "taxonomy@system");

        try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
            // 1. JSON → Blob
            ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
                    jsonContent.getBytes(StandardCharsets.UTF_8));

            // 2. Tree (preferences.json → blobId)
            TreeFormatter tree = new TreeFormatter();
            tree.append(PREFERENCES_FILENAME, FileMode.REGULAR_FILE, blobId);
            ObjectId treeId = inserter.insert(tree);

            // 3. Commit
            CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setTreeId(treeId);
            commitBuilder.setAuthor(personIdent);
            commitBuilder.setCommitter(personIdent);
            commitBuilder.setMessage(message != null ? message : "Preferences update");

            // Parent = current HEAD of main branch
            String refName = Constants.R_HEADS + MAIN_BRANCH;
            Ref branchRef = gitRepo.getRefDatabase().exactRef(refName);
            if (branchRef != null) {
                commitBuilder.setParentId(branchRef.getObjectId());
            }

            ObjectId commitId = inserter.insert(commitBuilder);
            inserter.flush();

            // 4. Update branch ref
            RefUpdate ru = gitRepo.updateRef(refName);
            ru.setNewObjectId(commitId);
            if (branchRef == null) {
                ru.setExpectedOldObjectId(ObjectId.zeroId());
            }
            ru.setForceUpdate(true);
            RefUpdate.Result result = ru.update();

            log.info("Committed preferences to '{}': {} (ref update: {})",
                    MAIN_BRANCH, commitId.name(), result);

            return commitId.name();
        }
    }

    /**
     * Read the preferences JSON at the HEAD of the {@code main} branch.
     *
     * @return the JSON string, or {@code null} if the branch has no commits yet
     * @throws IOException on JGit errors
     */
    public String readHead() throws IOException {
        String refName = Constants.R_HEADS + MAIN_BRANCH;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) return null;

        try (RevWalk walk = new RevWalk(gitRepo)) {
            RevCommit revCommit = walk.parseCommit(ref.getObjectId());
            return readJsonFromTree(revCommit.getTree());
        }
    }

    /**
     * Get the commit history of the {@code main} branch, newest first.
     *
     * @return list of preference commits (may be empty)
     * @throws IOException on JGit errors
     */
    public List<PreferencesCommit> getHistory() throws IOException {
        String refName = Constants.R_HEADS + MAIN_BRANCH;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) return List.of();

        List<PreferencesCommit> history = new ArrayList<>();
        try (RevWalk walk = new RevWalk(gitRepo)) {
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit c : walk) {
                PersonIdent authorIdent = c.getAuthorIdent();
                history.add(new PreferencesCommit(
                        c.name(),
                        authorIdent.getName(),
                        Instant.ofEpochSecond(c.getCommitTime()),
                        c.getFullMessage()));
            }
        }
        return history;
    }

    private String readJsonFromTree(org.eclipse.jgit.revwalk.RevTree tree) throws IOException {
        try (TreeWalk tw = TreeWalk.forPath(gitRepo, PREFERENCES_FILENAME, tree)) {
            if (tw == null) return null;
            ObjectId blobId = tw.getObjectId(0);
            ObjectLoader loader = gitRepo.open(blobId, Constants.OBJ_BLOB);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Check if this repository is backed by a database (Hibernate) or in-memory.
     *
     * @return {@code true} if backed by HibernateRepository (persistent)
     */
    public boolean isDatabaseBacked() {
        return gitRepo instanceof HibernateRepository;
    }
}
