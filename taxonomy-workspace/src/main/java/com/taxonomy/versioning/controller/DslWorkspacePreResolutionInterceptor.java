package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Resolves an isolated workspace and its exact repository before entering HTTP
 * endpoints that read or mutate workspace-scoped repository, hypothesis,
 * analysis or relation state.
 *
 * <p>Provisioning and context resolution failures propagate before controller
 * code runs. Successful results are request-cached by the corresponding
 * services, so repeated controller lookups cannot later switch repository or
 * workspace. Despite the historical class name, the interceptor is also used
 * by analysis and graph-search endpoints whose results carry workspace-scoped
 * hypotheses or relation visibility.</p>
 *
 * <p>An explicit central selection may read historical portfolio records through
 * GET/HEAD. Its repository, branch and actor must still match the request-bound
 * context; writes, analysis and Git operations require an isolated workspace.</p>
 */
@Component
public class DslWorkspacePreResolutionInterceptor implements HandlerInterceptor {

    // Keep this an explicit list: some GET endpoints (notably Copilot status)
    // can schedule work or promote snapshots, and therefore require isolation.
    private static final List<Pattern> PORTFOLIO_READ_PATHS = List.of(
            "/api/(?:projects|solutions|products)(?:/[0-9]+)?/?",
            "/api/projects/[0-9]+/requirements(?:/[0-9]+(?:/(?:versions|snapshots))?)?/?",
            "/api/projects/[0-9]+/(?:analysis-jobs(?:/[^/]+)?|snapshots/[^/]+|portfolio|conflicts|reports/[^/]+)/?",
            "/api/projects/[0-9]+/solutions(?:/[0-9]+/products)?/?")
            .stream().map(Pattern::compile).toList();

    private final WorkspaceResolver workspaceResolver;
    private final RepositoryStateService repositoryStateService;

    public DslWorkspacePreResolutionInterceptor(WorkspaceResolver workspaceResolver,
                                                RepositoryStateService repositoryStateService) {
        this.workspaceResolver = workspaceResolver;
        this.repositoryStateService = repositoryStateService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String username = workspaceResolver.resolveCurrentUsername();
        // 1.3 portfolios can legitimately belong to the central repository.
        // An explicit central selection may read those records, without creating
        // workspace state or turning historical data into a personal workspace.
        // Git operations and every mutation retain the isolation contract below.
        if (isExplicitCentralPortfolioRead(request)) {
            RepositoryContext repository = workspaceResolver.resolveCurrentRepositoryContext();
            WorkspaceContext workspace = workspaceResolver.resolveCurrentContext();
            if (repository == null || workspace == null
                    || repository.scope() != RepositoryScope.CENTRAL_READ
                    || workspace.workspaceId() != null
                    || !Objects.equals(repository.repositoryId(), workspace.repositoryId())
                    || !Objects.equals(repository.branch(), workspace.currentBranch())
                    || !Objects.equals(repository.username(), workspace.username())
                    || !Objects.equals(repository.username(), username)) {
                throw new IllegalStateException(
                        "Central portfolio read did not resolve one matching repository, branch and actor");
            }
            return true;
        }
        repositoryStateService.ensureWorkspaceState(username);

        // Resolve the repository first. Besides request-caching the mandatory
        // tenant identity, this persists the deterministic primary provenance
        // for any pre-migration workspace that still lacks source_repository_id.
        RepositoryContext repositoryContext =
                workspaceResolver.resolveCurrentRepositoryContext();
        WorkspaceContext workspaceContext = workspaceResolver.resolveCurrentContext();

        if (repositoryContext == null) {
            throw new IllegalStateException(
                    "Repository context resolver returned null for a workspace-scoped operation");
        }
        if (workspaceContext == null) {
            throw new IllegalStateException(
                    "Workspace context resolver returned null for a workspace-scoped operation");
        }
        // RepositoryContext permits a workspace ID only for WORKSPACE scope.
        // Central contexts now carry real repository/user identities and no
        // longer equal the legacy SHARED sentinel, so validate isolation itself.
        if (repositoryContext.workspaceId() == null
                || workspaceContext.workspaceId() == null
                || workspaceContext.workspaceId().isBlank()) {
            // An explicit central selection is valid for read APIs, not for these
            // isolated-workspace endpoints. Reject the caller without relaxing isolation.
            String requestedWorkspace =
                    WorkspaceContextResolver.requestedWorkspaceId(request);
            if (requestedWorkspace != null && requestedWorkspace.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Select an isolated workspace before starting this operation");
            }
            throw new IllegalStateException(
                    "Authenticated workspace-scoped operation did not resolve an isolated workspace");
        }
        if (!Objects.equals(
                repositoryContext.workspaceId(), workspaceContext.workspaceId())) {
            throw new IllegalStateException(
                    "Repository and workspace resolution selected different workspace identities");
        }
        return true;
    }

    private static boolean isExplicitCentralPortfolioRead(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (path == null) return false;
        String context = request.getContextPath();
        if (context != null && !context.isEmpty()) {
            if (!path.startsWith(context + "/")) return false;
            path = path.substring(context.length());
        }
        String requestPath = path;
        return "".equals(WorkspaceContextResolver.requestedWorkspaceId(request))
                && PORTFOLIO_READ_PATHS.stream()
                        .anyMatch(pattern -> pattern.matcher(requestPath).matches());
    }
}
