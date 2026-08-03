package com.taxonomy.versioning.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves a stable workspace before entering workspace-scoped HTTP endpoints.
 * Provisioning and resolution failures propagate before controller code runs.
 */
@Component
public class DslWorkspacePreResolutionInterceptor implements HandlerInterceptor {

    private final WorkspaceResolver workspaceResolver;
    private final RepositoryStateService repositoryStateService;
    private final boolean sharedModeEnabled;

    public DslWorkspacePreResolutionInterceptor(
            WorkspaceResolver workspaceResolver,
            RepositoryStateService repositoryStateService,
            @Value("${taxonomy.workspace.shared-mode-enabled:true}")
            boolean sharedModeEnabled) {
        this.workspaceResolver = workspaceResolver;
        this.repositoryStateService = repositoryStateService;
        this.sharedModeEnabled = sharedModeEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String username = workspaceResolver.resolveCurrentUsername();
        repositoryStateService.ensureWorkspaceState(username);
        WorkspaceContext context = workspaceResolver.resolveCurrentContext();
        if (WorkspaceContext.SHARED.equals(context) && !sharedModeEnabled) {
            throw new IllegalStateException(
                    "Authenticated workspace-scoped operation did not resolve an isolated workspace");
        }
        return true;
    }
}
