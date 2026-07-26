package com.taxonomy.preferences.storage;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JGit facade that versions preferences as one JSON document.
 *
 * <p>The Taxonomy-specific file and branch conventions remain here. Persistent
 * object, ref and reflog storage is supplied by {@code jgit-storage-hibernate-core}
 * through its public factory contract.</p>
 */
public class PreferencesGitRepository implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PreferencesGitRepository.class);
    private static final String REPOSITORY_NAME = "taxonomy-preferences";

    /** The single JSON file stored in every commit tree. */
    static final String PREFERENCES_FILENAME = "preferences.json";

    /** The single branch used for preferences history. */
    static final String MAIN_BRANCH = "main";

    private final Repository gitRepo;
    private final HibernateGitStorage storageHandle;
    private final boolean databaseBacked;
    private final boolean closeRepository;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Open the persistent preferences repository through the reusable storage library. */
    public PreferencesGitRepository(HibernateRepositoryFactory storageFactory) {
        this(Objects.requireNonNull(storageFactory, "storageFactory")
                .open(new RepositoryName(REPOSITORY_NAME)));
    }

    private PreferencesGitRepository(HibernateGitStorage storageHandle) {
        this.gitRepo = storageHandle.repository();
        this.storageHandle = storageHandle;
        this.databaseBacked = true;
        this.closeRepository = false;
        log.info("Initialised database-backed PreferencesGitRepository");
    }

    /** Create an owned, volatile in-memory repository for unit tests. */
    public PreferencesGitRepository() {
        this(new InMemoryRepository(new DfsRepositoryDescription(REPOSITORY_NAME)), true);
    }

    /** Use a caller-supplied JGit repository without taking ownership of it. */
    public PreferencesGitRepository(Repository gitRepo) {
        this(gitRepo, false);
    }

    PreferencesGitRepository(Repository gitRepo, boolean closeRepository) {
        this.gitRepo = Objects.requireNonNull(gitRepo, "gitRepo");
        this.storageHandle = null;
        this.databaseBacked = false;
        this.closeRepository = closeRepository;
        log.info("Initialised PreferencesGitRepository ({})", gitRepo.getClass().getSimpleName());
    }

    /**
     * Commit preferences JSON to the {@code main} branch.
     *
     * @return the new commit SHA
     * @throws IOException if object insertion or the optimistic ref update fails
     */
    public String commit(String jsonContent, String author, String message) throws IOException {
        PersonIdent personIdent = personIdent(author);
        String commitMessage = message != null ? message : "Preferences update";

        try (ObjectInserter inserter = gitRepo.newObjectInserter()) {
            ObjectId blobId = inserter.insert(Constants.OBJ_BLOB,
                    jsonContent.getBytes(StandardCharsets.UTF_8));

            TreeFormatter tree = new TreeFormatter();
            tree.append(PREFERENCES_FILENAME, FileMode.REGULAR_FILE, blobId);
            ObjectId treeId = inserter.insert(tree);

            String refName = Constants.R_HEADS + MAIN_BRANCH;
            Ref branchRef = gitRepo.getRefDatabase().exactRef(refName);

            CommitBuilder commitBuilder = new CommitBuilder();
            commitBuilder.setTreeId(treeId);
            commitBuilder.setAuthor(personIdent);
            commitBuilder.setCommitter(personIdent);
            commitBuilder.setMessage(commitMessage);
            if (branchRef != null) {
                commitBuilder.setParentId(branchRef.getObjectId());
            }

            ObjectId commitId = inserter.insert(commitBuilder);
            inserter.flush();

            RefUpdate update = gitRepo.updateRef(refName);
            update.setNewObjectId(commitId);
            update.setExpectedOldObjectId(
                    branchRef != null ? branchRef.getObjectId() : ObjectId.zeroId());
            update.setForceUpdate(false);
            update.setRefLogIdent(personIdent);
            update.setRefLogMessage("commit: " + commitMessage, true);
            RefUpdate.Result result = update.update();
            requireSuccessfulUpdate(refName, result);

            log.info("Committed preferences to '{}': {} (ref update: {})",
                    MAIN_BRANCH, commitId.name(), result);
            return commitId.name();
        }
    }

    /** Read the preferences JSON at the HEAD of {@code main}. */
    public String readHead() throws IOException {
        String refName = Constants.R_HEADS + MAIN_BRANCH;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) {
            return null;
        }

        try (RevWalk walk = new RevWalk(gitRepo)) {
            return readJsonFromTree(walk.parseCommit(ref.getObjectId()).getTree());
        }
    }

    /** Return preference commits newest first. */
    public List<PreferencesCommit> getHistory() throws IOException {
        String refName = Constants.R_HEADS + MAIN_BRANCH;
        Ref ref = gitRepo.getRefDatabase().exactRef(refName);
        if (ref == null) {
            return List.of();
        }

        List<PreferencesCommit> history = new ArrayList<>();
        try (RevWalk walk = new RevWalk(gitRepo)) {
            walk.markStart(walk.parseCommit(ref.getObjectId()));
            for (RevCommit commit : walk) {
                PersonIdent authorIdent = commit.getAuthorIdent();
                history.add(new PreferencesCommit(
                        commit.name(),
                        authorIdent.getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()),
                        commit.getFullMessage()));
            }
        }
        return history;
    }

    private String readJsonFromTree(org.eclipse.jgit.revwalk.RevTree tree) throws IOException {
        try (TreeWalk treeWalk = TreeWalk.forPath(gitRepo, PREFERENCES_FILENAME, tree)) {
            if (treeWalk == null) {
                return null;
            }
            ObjectLoader loader = gitRepo.open(treeWalk.getObjectId(0), Constants.OBJ_BLOB);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PersonIdent personIdent(String author) {
        String name = author != null ? author : "taxonomy";
        String email = author != null ? author : "taxonomy@system";
        return new PersonIdent(name, email);
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

    /** Expose the underlying public JGit repository for advanced read operations. */
    public Repository getGitRepository() {
        return gitRepo;
    }

    /** Return whether this instance uses persistent Hibernate-backed storage. */
    public boolean isDatabaseBacked() {
        return databaseBacked;
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
