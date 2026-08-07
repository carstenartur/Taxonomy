package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
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

    /** The well-known storage name retained for the migrated primary repository. */
    static final String SYSTEM_REPO_NAME = SystemRepositoryService.PRIMARY_STORAGE_NAME;

    /** Prefix for workspace repository names. */
    static final String WORKSPACE_REPO_PREFIX = "ws-";

    /** Fallback prefix used by in-memory tests without a repository catalog. */
    static final String CENTRAL_REPO_PREFIX = "central-";

    private final HibernateRepositoryFactory storageFactory;
    private final SystemRepositoryService systemRepositoryService;
    private final ConcurrentMap<String, DslGitRepository> cache = new ConcurrentHashMap<>();

    /** Legacy/in-memory constructor retained for tests. */
    public DslGitRepositoryFactory(HibernateRepositoryFactory storageFactory) {
        this(storageFactory, null);
    }

    /** Create a logical-repository factory with explicit catalog resolution. */
    public DslGitRepositoryFactory(
            HibernateRepositoryFactory storageFactory,
            SystemRepositoryService systemRepositoryService) {
        this.storageFactory = storageFactory;
        this.systemRepositoryService = systemRepositoryService;
    }

    /**
     * Return the migrated primary repository.
     *
     * @deprecated repository-sensitive code should resolve an explicit repository ID.
     */
    @Deprecated(forRemoval = false)
    public DslGitRepository getSystemRepository() {
        return cache.computeIfAbsent(SYSTEM_REPO_NAME, this::createRepository);
    }

    /** Return one explicitly selected central architecture repository. */
    public DslGitRepository getCentralRepository(String repositoryId) {
        return cache.computeIfAbsent(storageNameFor(repositoryId), this::createRepository);
    }

    /** Initialize/open a central repository under an already-reserved catalog storage name. */
    public DslGitRepository createCentralRepository(String repositoryId, String storageName) {
        requireText(repositoryId, "repositoryId");
        requireText(storageName, "storageName");
        if (systemRepositoryService != null) {
            SystemRepository metadata = systemRepositoryService.getRepository(repositoryId);
            if (!storageName.equals(metadata.getStorageRepositoryName())) {
                throw new IllegalArgumentException(
                        "Storage name does not match repository catalog entry: " + repositoryId);
            }
        }
        return cache.computeIfAbsent(storageName, this::createRepository);
    }

    /**
     * Return a repository isolated by workspace ID.
     *
     * <p>This compatibility path retains the historic primary-repository seed.
     * New multi-repository provisioning must use
     * {@link #createWorkspaceRepository(String, String, String)}.</p>
     */
    public DslGitRepository getWorkspaceRepository(String workspaceId) {
        String repoName = workspaceRepositoryName(workspaceId);
        return cache.computeIfAbsent(repoName, name -> {
            DslGitRepository repository = createRepository(name);
            seedFromCentral(repository, getSystemRepository(), "draft", "system repository");
            return repository;
        });
    }

    /**
     * Open an existing workspace repository without inferring or seeding any source.
     * Explicit repository contexts use this path so a missing workspace can never
     * be accidentally initialized from the primary repository.
     */
    public DslGitRepository openWorkspaceRepository(String workspaceId) {
        return cache.computeIfAbsent(
                workspaceRepositoryName(workspaceId), this::createRepository);
    }

    /** Create/open an isolated workspace and seed it from the selected central source. */
    public DslGitRepository createWorkspaceRepository(
            String workspaceId,
            String sourceRepositoryId,
            String sourceBranch) {
        String repoName = workspaceRepositoryName(workspaceId);
        DslGitRepository source = getCentralRepository(sourceRepositoryId);
        String branch = requireText(sourceBranch, "sourceBranch");
        return cache.computeIfAbsent(repoName, name -> {
            DslGitRepository repository = createRepository(name);
            seedFromCentral(repository, source, branch,
                    "central repository " + sourceRepositoryId);
            return repository;
        });
    }

    /** Resolve the repository selected by an explicit repository context. */
    public DslGitRepository resolveRepository(RepositoryContext context) {
        if (context == null) {
            throw new IllegalArgumentException("RepositoryContext must not be null");
        }
        if (context.workspaceId() != null) {
            return openWorkspaceRepository(context.workspaceId());
        }
        return getCentralRepository(context.repositoryId());
    }

    /**
     * Resolve the legacy workspace context.
     *
     * @deprecated use {@link #resolveRepository(RepositoryContext)} when repository
     *             identity is available.
     */
    @Deprecated(forRemoval = false)
    public DslGitRepository resolveRepository(WorkspaceContext context) {
        if (context == null || context.workspaceId() == null) {
            return getSystemRepository();
        }
        return getWorkspaceRepository(context.workspaceId());
    }

    /** Close and remove a cached workspace handle without deleting persisted data. */
    public void evict(String workspaceId) {
        closeQuietly(cache.remove(workspaceRepositoryName(workspaceId)));
    }

    /** Delete all persisted Git state belonging to one workspace repository. */
    public RepositoryDeletionResult deleteWorkspaceRepository(String workspaceId) {
        String repoName = workspaceRepositoryName(workspaceId);
        closeQuietly(cache.remove(repoName));
        if (storageFactory == null) {
            return new RepositoryDeletionResult(0, 0, 0);
        }
        return storageFactory.deleteRepository(new RepositoryName(repoName));
    }

    /** Delete storage for a non-primary central repository. */
    public RepositoryDeletionResult deleteCentralRepository(String repositoryId) {
        String storageName = storageNameFor(repositoryId);
        if (SYSTEM_REPO_NAME.equals(storageName)) {
            throw new IllegalArgumentException("The primary repository cannot be deleted");
        }
        closeQuietly(cache.remove(storageName));
        if (storageFactory == null) {
            return new RepositoryDeletionResult(0, 0, 0);
        }
        return storageFactory.deleteRepository(new RepositoryName(storageName));
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

    private void seedFromCentral(
            DslGitRepository workspace,
            DslGitRepository source,
            String sourceBranch,
            String sourceDescription) {
        try {
            if (!workspace.getBranchNames().isEmpty()) {
                log.debug("Workspace repository already contains refs; skipping seed");
                return;
            }

            String dsl = source.getDslAtHead(sourceBranch);
            if (dsl != null && !dsl.isBlank()) {
                workspace.commitDsl("draft", dsl, "system",
                        "Initial working copy from " + sourceDescription + "/" + sourceBranch);
                log.info("Seeded workspace repository from {}/{}",
                        sourceDescription, sourceBranch);
            } else {
                log.debug("Source branch {}/{} is empty; skipping workspace seed",
                        sourceDescription, sourceBranch);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to seed workspace repository from " + sourceDescription,
                    exception);
        }
    }

    private String storageNameFor(String repositoryId) {
        String id = requireText(repositoryId, "repositoryId");
        if (systemRepositoryService == null) {
            return CENTRAL_REPO_PREFIX + id;
        }
        SystemRepository metadata = systemRepositoryService.getRepository(id);
        String storageName = metadata.getStorageRepositoryName();
        if (storageName == null || storageName.isBlank()) {
            if (metadata.isPrimaryRepo()) {
                return SYSTEM_REPO_NAME;
            }
            throw new IllegalStateException(
                    "Repository has no storageRepositoryName: " + repositoryId);
        }
        return storageName;
    }

    private static String workspaceRepositoryName(String workspaceId) {
        return WORKSPACE_REPO_PREFIX + requireText(workspaceId, "workspaceId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
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
