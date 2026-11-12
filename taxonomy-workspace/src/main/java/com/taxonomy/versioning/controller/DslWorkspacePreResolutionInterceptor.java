package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceProvisionPermission;
import org.springframework.beans.factory.annotation.Autowired;
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
 * workspace. The interceptor also covers analysis endpoints whose results
 * carry workspace-scoped hypotheses.</p>
 *
 * <p>An explicit central selection, or a reader without provisioning capability,
 * may read historical portfolio records through GET/HEAD. Its repository, branch and
 * actor must still match the request-bound context; writes, analysis and Git
 * operations require an isolated workspace.</p>
 */
@Component
public class DslWorkspacePreResolutionInterceptor implements HandlerInterceptor {

    // Keep this list explicit: GET handlers outside these observational routes
    // may schedule work or promote snapshots and therefore require isolation.
    private static final List<Pattern> CENTRAL_READ_PATHS = List.of(
            "/api/search/graph",
            "/api/(?:projects|solutions|products)(?:/[0-9]+)?/?",
            "/api/projects/[0-9]+/requirements(?:/[0-9]+(?:/(?:versions|snapshots))?)?/?",
            "/api/projects/[0-9]+/(?:analysis-jobs(?:/[^/]+)?|snapshots/[^/]+|portfolio|conflicts|reports/[^/]+)/?",
            "/api/projects/[0-9]+/copilot-operations/[^/]+/?",
            "/api/projects/[0-9]+/requirements/[0-9]+/copilot/latest/?",
            "/api/projects/[0-9]+/solutions(?:/[0-9]+/products)?/?",
            "/api/projects/[0-9]+/architecture-workbench/[^/]+/?")
            .stream().map(Pattern::compile).toList();

    private static final Pattern HYPOTHESIS_READ = Pattern.compile(
            "/api/dsl/hypotheses(?:/head|/[0-9]+/evidence)?/?");

    private final WorkspaceResolver workspaceResolver;
    private final RepositoryStateService repositoryStateService;
    private WorkspaceProvisionPermission provisionPermission = actor -> true;

    @Autowired(required = false)
    void setWorkspaceProvisionPermission(WorkspaceProvisionPermission provisionPermission) {
        this.provisionPermission = Objects.requireNonNull(provisionPermission);
    }

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
        String readPath = observationalPath(request);
        if (HYPOTHESIS_READ.matcher(readPath).matches()) {
            Objects.requireNonNull(workspaceResolver.resolveCurrentRepositoryContextForRead());
            return true;
        }
        boolean mayProvision = provisionPermission.mayProvision(username);
        boolean explicitCentral = "".equals(WorkspaceContextResolver.requestedWorkspaceId(request));
        // 1.3 portfolios belong to the central repository.
        // An explicit central selection may read them without creating workspace
        // state or turning historical data into a personal workspace.
        // Git operations and every mutation retain the isolation contract below.
        if (CENTRAL_READ_PATHS.stream().anyMatch(pattern -> pattern.matcher(readPath).matches())) {
            if (explicitCentral || !mayProvision) {
                RepositoryContext repository = workspaceResolver.resolveCurrentRepositoryContext();
                WorkspaceContext workspace = workspaceResolver.resolveCurrentContext();
                // A reader's existing working copy remains selected. Only an actual
                // central context can take the path that avoids workspace allocation.
                if (explicitCentral || repository == null || repository.scope() != RepositoryScope.WORKSPACE) {
                    if (repository == null || workspace == null
                            || repository.scope() != RepositoryScope.CENTRAL_READ
                            || workspace.workspaceId() != null
                            || !Objects.equals(repository.repositoryId(), workspace.repositoryId())
                            || !Objects.equals(repository.branch(), workspace.currentBranch())
                            || !Objects.equals(repository.username(), workspace.username())
                            || !Objects.equals(repository.username(), username)) {
                        throw new IllegalStateException(
                                "Central read did not resolve one matching repository, branch and actor");
                    }
                    return true;
                }
            }
        }
        if (explicitCentral) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Select an isolated workspace before starting this operation");
        }
        if (!mayProvision) {
            RepositoryContext repository = workspaceResolver.resolveCurrentRepositoryContext();
            WorkspaceContext workspace = workspaceResolver.resolveCurrentContext();
            if (repository != null && workspace != null
                    && (repository.workspaceId() == null || workspace.workspaceId() == null
                    || workspace.workspaceId().isBlank())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "An existing isolated workspace is required for this operation");
            }
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

    private static String observationalPath(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            return "";
        }
        String path = request.getRequestURI();
        if (path == null) return "";
        String context = request.getContextPath();
        if (context != null && !context.isEmpty()) {
            if (!path.startsWith(context + "/")) return "";
            path = path.substring(context.length());
        }
        return path;
    }
}
