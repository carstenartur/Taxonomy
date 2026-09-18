package com.taxonomy.workspace.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;

/**
 * Validates every explicit browser-tab workspace pin before controller code can
 * catch a resolver exception and accidentally continue in a shared context.
 *
 * <p>Requests without an explicit pin retain the existing endpoint-specific
 * provisioning behaviour. Requests carrying either the custom header or the
 * equivalent query parameter must resolve to that exact owned workspace. The
 * header has the same precedence as {@link WorkspaceContextResolver}.</p>
 */
@Component
public class ExplicitWorkspacePinValidationInterceptor implements HandlerInterceptor {

    private final WorkspaceResolver workspaceResolver;

    public ExplicitWorkspacePinValidationInterceptor(WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String requestedWorkspaceId = explicitWorkspaceId(request);
        if (requestedWorkspaceId == null) {
            return true;
        }

        if (handler instanceof HandlerMethod method
                && method.hasMethodAnnotation(WorkspaceMetadataOperation.class)) {
            var metadata = workspaceResolver.resolveCurrentWorkspaceMetadata();
            if (metadata == null || !requestedWorkspaceId.equals(metadata.getWorkspaceId())) {
                throw new AccessDeniedException(
                        "Pinned workspace does not match the resolved lifecycle metadata");
            }
            return true;
        }

        RepositoryContext resolved = workspaceResolver.resolveCurrentRepositoryContext();
        if (resolved.scope() != RepositoryScope.WORKSPACE
                || !requestedWorkspaceId.equals(resolved.workspaceId())) {
            throw new AccessDeniedException(
                    "Pinned workspace does not match the resolved request context");
        }
        return true;
    }

    private static String explicitWorkspaceId(HttpServletRequest request) {
        String selected = WorkspaceContextResolver.requestedWorkspaceId(request);
        // The resolver represents an explicit central selection as the empty string.
        // There is no workspace ownership check to perform for that selection.
        return selected == null || selected.isEmpty() ? null : selected;
    }
}
