package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.WorkspaceContext;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for creating and caching {@link DslGitRepository} instances.
 *
 * <p>Provides per-workspace Git repository isolation by creating logically
 * separate repositories (each with its own {@code repositoryName}) in the
 * same database. The system repository (shared "draft" branch, legacy data)
 * uses the well-known name {@code "taxonomy-dsl"}.
 *
 * <p>Instances are cached by repository name so that repeated lookups for
 * the same workspace return the same {@link DslGitRepository} object.
 *
 * <p>This class is instantiated as a Spring {@code @Bean} in
 * {@link DslStorageConfig} — do not add {@code @Service} or
 * {@code @Component} here to avoid duplicate bean registration.
 */
public class DslGitRepositoryFactory {

    private static final Logger log = LoggerFactory.getLogger(DslGitRepositoryFactory.class);

    /** The well-known repository name for the system (shared) repository. */
    static final String SYSTEM_REPO_NAME = "taxonomy-dsl";

    /** Prefix for workspace repository names. */
    static final String WORKSPACE_REPO_PREFIX = "ws-";

    private final SessionFactory sessionFactory;
    private final ConcurrentMap<String, DslGitRepository> cache = new ConcurrentHashMap<>();

    public DslGitRepositoryFactory(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * Get the system repository (shared "draft" branch, legacy data).
     *
     * @return the system-wide shared DslGitRepository
     */
    public DslGitRepository getSystemRepository() {
        return cache.computeIfAbsent(SYSTEM_REPO_NAME, this::createRepository);
    }

    /**
     * Get a per-workspace repository with a logically separate Git namespace.
     *
     * @param workspaceId the workspace identifier
     * @return the workspace-specific DslGitRepository
     */
    public DslGitRepository getWorkspaceRepository(String workspaceId) {
        String repoName = WORKSPACE_REPO_PREFIX + workspaceId;
        return cache.computeIfAbsent(repoName, this::createRepository);
    }

    /**
     * Resolve the appropriate repository based on a {@link WorkspaceContext}.
     *
     * <p>If the context is {@code null} or has no workspace ID (shared context),
     * the system repository is returned. Otherwise, the per-workspace repository
     * for the given workspace ID is returned.
     *
     * @param ctx the workspace context (may be null)
     * @return the resolved DslGitRepository
     */
    public DslGitRepository resolveRepository(WorkspaceContext ctx) {
        if (ctx == null || ctx.workspaceId() == null) {
            return getSystemRepository();
        }
        return getWorkspaceRepository(ctx.workspaceId());
    }

    /**
     * Evict a workspace repository from the cache.
     *
     * <p>Call this when a workspace is deleted to free the cached instance.
     *
     * @param workspaceId the workspace identifier to evict
     */
    public void evict(String workspaceId) {
        cache.remove(WORKSPACE_REPO_PREFIX + workspaceId);
    }

    /**
     * Create a new DslGitRepository for the given name.
     *
     * <p>If a {@link SessionFactory} is available, creates a database-backed
     * repository. Otherwise (e.g. in tests), creates an in-memory repository.
     *
     * @param name the logical repository name
     * @return a new DslGitRepository instance
     */
    protected DslGitRepository createRepository(String name) {
        if (sessionFactory != null) {
            log.info("Creating database-backed DslGitRepository '{}'", name);
            return new DslGitRepository(sessionFactory, name);
        }
        log.info("Creating in-memory DslGitRepository '{}' (test mode)", name);
        return new DslGitRepository(
                new InMemoryRepository(new DfsRepositoryDescription(name)));
    }
}
