package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.WorkspaceContext;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates and caches Taxonomy's logical Git repositories.
 *
 * <p>The application owns repository naming, workspace routing and seeding. The
 * reusable {@code jgit-storage-hibernate-core} library owns database-backed JGit
 * object, ref and reflog persistence.</p>
 */
public class DslGitRepositoryFactory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DslGitRepositoryFactory.class);

    /** The well-known repository name for the system (shared) repository. */
    static final String SYSTEM_REPO_NAME = "taxonomy-dsl";

    /** Prefix for workspace repository names. */
    static final String WORKSPACE_REPO_PREFIX = "ws-";

    private final HibernateRepositoryFactory storageFactory;
    private final ConcurrentMap<String, DslGitRepository> cache = new ConcurrentHashMap<>();

    /**
     * Create a logical-repository factory.
     *
     * @param storageFactory reusable database storage factory, or {@code null} for in-memory tests
     */
    public DslGitRepositoryFactory(HibernateRepositoryFactory storageFactory) {
        this.storageFactory = storageFactory;
    }

    /** Return the shared system repository. */
    public DslGitRepository getSystemRepository() {
        return cache.computeIfAbsent(SYSTEM_REPO_NAME, this::createRepository);
    }

    /**
     * Return a repository isolated by workspace ID.
     *
     * <p>A newly created, empty repository is seeded from the shared draft. A
     * database repository reopened after cache eviction already has refs and is
     * therefore not seeded again.</p>
     */
    public DslGitRepository getWorkspaceRepository(String workspaceId) {
        String repoName = workspaceRepositoryName(workspaceId);
        return cache.computeIfAbsent(repoName, name -> {
            DslGitRepository repository = createRepository(name);
            seedFromSystemRepo(repository);
            return repository;
        });
    }

    /** Resolve the repository selected by a workspace context. */
    public DslGitRepository resolveRepository(WorkspaceContext context) {
        if (context == null || context.workspaceId() == null) {
            return getSystemRepository();
        }
        return getWorkspaceRepository(context.workspaceId());
    }

    /**
     * Close and remove a cached workspace handle without deleting persisted data.
     *
     * @param workspaceId workspace identifier
     */
    public void evict(String workspaceId) {
        closeQuietly(cache.remove(workspaceRepositoryName(workspaceId)));
    }

    /**
     * Delete all persisted Git state belonging to one workspace repository.
     *
     * <p>The cached handle is closed before invoking the storage library because
     * deletion is deliberately rejected while any coordinated handle remains
     * open. Other logical repository names are unaffected.</p>
     *
     * @param workspaceId workspace identifier
     * @return counts of deleted storage rows; zeroes in in-memory test mode
     */
    public RepositoryDeletionResult deleteWorkspaceRepository(String workspaceId) {
        String repoName = workspaceRepositoryName(workspaceId);
        closeQuietly(cache.remove(repoName));
        if (storageFactory == null) {
            return new RepositoryDeletionResult(0, 0, 0);
        }
        return storageFactory.deleteRepository(new RepositoryName(repoName));
    }

    /** Create either a persistent library-backed repository or an owned in-memory repository. */
    protected DslGitRepository createRepository(String name) {
        if (storageFactory != null) {
            log.info("Opening database-backed DslGitRepository '{}'", name);
            return new DslGitRepository(storageFactory, name);
        }
        log.info("Creating in-memory DslGitRepository '{}' (test mode)", name);
        return new DslGitRepository(
                new InMemoryRepository(new DfsRepositoryDescription(name)), true);
    }

    private void seedFromSystemRepo(DslGitRepository workspace) {
        try {
            if (!workspace.getBranchNames().isEmpty()) {
                log.debug("Workspace repository already contains refs; skipping seed");
                return;
            }

            DslGitRepository system = getSystemRepository();
            String dsl = system.getDslAtHead("draft");
            if (dsl != null && !dsl.isBlank()) {
                workspace.commitDsl("draft", dsl, "system",
                        "Initial clone from system repository");
                log.info("Seeded workspace repository from system draft branch");
            } else {
                log.debug("System draft branch is empty; skipping workspace seed");
            }
        } catch (IOException exception) {
            log.warn("Failed to seed workspace repository from system repository: {}",
                    exception.getMessage());
        }
    }

    private static String workspaceRepositoryName(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        return WORKSPACE_REPO_PREFIX + workspaceId;
    }

    private static void closeQuietly(DslGitRepository repository) {
        if (repository == null) {
            return;
        }
        try {
            repository.close();
        } catch (RuntimeException exception) {
            log.warn("Failed to close cached Git repository: {}", exception.getMessage());
        }
    }

    /** Close all cached repository handles during application shutdown. */
    @Override
    public void close() {
        cache.values().forEach(DslGitRepositoryFactory::closeQuietly);
        cache.clear();
    }
}
