package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves the current workspace and repository contexts from the authenticated
 * principal and persistent workspace metadata.
 *
 * <p>The legacy {@link WorkspaceContext} is retained while existing callers are
 * migrated. New repository-sensitive code must use {@link RepositoryContext} so
 * a {@code null} workspace can never mean an unspecified global repository.</p>
 */
@Service
public class WorkspaceContextResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextResolver.class);

    private final WorkspaceManager workspaceManager;
    private final SystemRepositoryService systemRepositoryService;
    private final UserWorkspaceRepository workspaceRepository;

    @Autowired
    public WorkspaceContextResolver(WorkspaceManager workspaceManager,
                                    SystemRepositoryService systemRepositoryService,
                                    UserWorkspaceRepository workspaceRepository) {
        this.workspaceManager = workspaceManager;
        this.systemRepositoryService = systemRepositoryService;
        this.workspaceRepository = workspaceRepository;
    }

    /** Compatibility constructor for focused unit tests without persistence. */
    public WorkspaceContextResolver(WorkspaceManager workspaceManager,
                                    SystemRepositoryService systemRepositoryService) {
        this(workspaceManager, systemRepositoryService, null);
    }

    /** Resolve the legacy workspace context for the currently authenticated user. */
    public WorkspaceContext resolveCurrentContext() {
        return resolveForUser(resolveUsername());
    }

    /**
     * Resolve the legacy workspace context for a specific user.
     *
     * <p>Only users with an explicitly provisioned persistent workspace receive
     * a workspace-scoped context. Callers that need repository identity must use
     * {@link #resolveRepositoryContextForUser(String)} instead.</p>
     */
    public WorkspaceContext resolveForUser(String username) {
        if (username == null || username.isBlank()
                || WorkspaceManager.DEFAULT_USER.equals(username)) {
            return WorkspaceContext.SHARED;
        }

        UserWorkspace workspace = findWorkspace(username);
        if (workspace != null && hasText(workspace.getWorkspaceId())) {
            String branch = hasText(workspace.getCurrentBranch())
                    ? workspace.getCurrentBranch().strip()
                    : systemRepositoryService.getSharedBranch();
            log.debug("Resolved workspace context for user '{}': workspace={}, branch={}",
                    username, workspace.getWorkspaceId(), branch);
            return new WorkspaceContext(
                    username.strip(), workspace.getWorkspaceId().strip(), branch);
        }

        log.debug("No provisioned workspace for user '{}'; falling back to SHARED", username);
        return WorkspaceContext.SHARED;
    }

    /** Resolve an explicit repository context for the authenticated user. */
    public RepositoryContext resolveCurrentRepositoryContext() {
        return resolveRepositoryContextForUser(resolveUsername());
    }

    /**
     * Resolve the selected logical repository and optional workspace for one user.
     *
     * <p>A user without a personal workspace receives an explicit read-only
     * context for the primary central repository. A legacy workspace without
     * {@code sourceRepositoryId} is assigned to that same primary repository,
     * which is the only repository such rows could originate from before the
     * multi-repository migration. The inferred provenance is persisted so
     * remaining legacy adapters cannot later resolve the same workspace against
     * an unspecified repository.</p>
     */
    public RepositoryContext resolveRepositoryContextForUser(String username) {
        String user = normalizeUsername(username);
        UserWorkspace workspace = WorkspaceManager.DEFAULT_USER.equals(user)
                ? null
                : findWorkspace(user);

        if (workspace != null && hasText(workspace.getWorkspaceId())) {
            SystemRepository repository = resolveWorkspaceSource(workspace);
            String branch = hasText(workspace.getCurrentBranch())
                    ? workspace.getCurrentBranch().strip()
                    : requireBranch(repository);
            RepositoryContext context = RepositoryContext.workspace(
                    requireRepositoryId(repository),
                    workspace.getWorkspaceId().strip(),
                    branch,
                    user);
            log.debug(
                    "Resolved repository context for user '{}': repository={}, workspace={}, branch={}",
                    user, context.repositoryId(), context.workspaceId(), context.branch());
            return context;
        }

        SystemRepository primary = systemRepositoryService.getPrimaryRepository();
        RepositoryContext context = RepositoryContext.centralRead(
                requireRepositoryId(primary), requireBranch(primary), user);
        log.debug(
                "Resolved central repository context for user '{}': repository={}, branch={}",
                user, context.repositoryId(), context.branch());
        return context;
    }

    private UserWorkspace findWorkspace(String username) {
        UserWorkspace workspace = workspaceManager.findActiveWorkspace(username);
        return workspace != null ? workspace : workspaceManager.findUserWorkspace(username);
    }

    private SystemRepository resolveWorkspaceSource(UserWorkspace workspace) {
        if (hasText(workspace.getSourceRepositoryId())) {
            return systemRepositoryService.getRepository(
                    workspace.getSourceRepositoryId().strip());
        }

        SystemRepository primary = systemRepositoryService.getPrimaryRepository();
        String primaryRepositoryId = requireRepositoryId(primary);
        workspace.setSourceRepositoryId(primaryRepositoryId);
        if (workspaceRepository != null) {
            workspaceRepository.save(workspace);
        }
        log.info(
                "Persisted primary repository {} as source provenance for legacy workspace {}",
                primaryRepositoryId, workspace.getWorkspaceId());
        return primary;
    }

    private static String requireRepositoryId(SystemRepository repository) {
        if (repository == null || !hasText(repository.getRepositoryId())) {
            throw new IllegalStateException("Resolved repository has no repositoryId");
        }
        return repository.getRepositoryId().strip();
    }

    private static String requireBranch(SystemRepository repository) {
        if (repository == null || !hasText(repository.getDefaultBranch())) {
            throw new IllegalStateException("Resolved repository has no default branch");
        }
        return repository.getDefaultBranch().strip();
    }

    private static String normalizeUsername(String username) {
        return username == null || username.isBlank()
                ? WorkspaceManager.DEFAULT_USER
                : username.strip();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String resolveUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return authentication.getName();
        }
        return WorkspaceManager.DEFAULT_USER;
    }
}
