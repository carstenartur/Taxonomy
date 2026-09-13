package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the current workspace and repository contexts from the authenticated
 * principal and persistent workspace metadata.
 *
 * <p>The legacy {@link WorkspaceContext} is retained while existing callers are
 * migrated. New repository-sensitive code must use {@link RepositoryContext} so
 * a {@code null} workspace can never mean an unspecified global repository.</p>
 *
 * <p>Browser tabs may pin their exact workspace with
 * {@value #WORKSPACE_HEADER}. Native {@code EventSource} cannot set a custom
 * header, so SSE endpoints may use the equivalent
 * {@value #WORKSPACE_QUERY_PARAMETER} query parameter. This prevents another
 * session of the same user from silently changing the repository context of an
 * already open page.</p>
 */
@Service
public class WorkspaceContextResolver {

    /** Request header used by one browser tab to bind API calls to its workspace. */
    public static final String WORKSPACE_HEADER = "X-Taxonomy-Workspace-Id";

    /** Query parameter used only when a browser transport cannot set headers. */
    public static final String WORKSPACE_QUERY_PARAMETER = "workspaceId";

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

        UserWorkspace workspace = resolveWorkspace(username);
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
                : resolveWorkspace(user);

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

    private UserWorkspace resolveWorkspace(String username) {
        String requestedWorkspaceId = requestedWorkspaceId();
        if (requestedWorkspaceId == null) {
            return findWorkspace(username);
        }

        UserWorkspace workspace = workspaceManager.getWorkspaceById(requestedWorkspaceId);
        if (workspace == null
                || !hasText(workspace.getUsername())
                || !workspace.getUsername().strip().equals(username.strip())
                || workspace.isArchived()
                || workspace.isShared()) {
            throw new AccessDeniedException(
                    "Requested workspace is not available to the authenticated user");
        }
        return workspace;
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

    private static String requestedWorkspaceId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return null;
        }
        String header = servletAttributes.getRequest().getHeader(WORKSPACE_HEADER);
        if (hasText(header)) {
            return header.strip();
        }
        String parameter = servletAttributes.getRequest()
                .getParameter(WORKSPACE_QUERY_PARAMETER);
        return hasText(parameter) ? parameter.strip() : null;
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
