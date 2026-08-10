package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

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
 */
@Component
public class DslWorkspacePreResolutionInterceptor implements HandlerInterceptor {

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
        if (WorkspaceContext.SHARED.equals(workspaceContext)) {
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
}
