package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the workspace before entering DSL endpoints that read or mutate
 * repository- or hypothesis-scoped state.
 *
 * <p>Provisioning and context resolution failures propagate before controller
 * code runs. Successful results are request-cached by the corresponding
 * services, so repeated controller lookups cannot later switch to a shared
 * context.</p>
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
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        if (WorkspaceContext.SHARED.equals(context)) {
            throw new IllegalStateException(
                    "Authenticated DSL operation did not resolve an isolated workspace");
        }
        return true;
    }
}
