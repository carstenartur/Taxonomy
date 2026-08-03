package com.taxonomy.workspace.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the current workspace username and request-bound workspace context.
 *
 * <p>A successfully resolved context is cached for the lifetime of the HTTP
 * request. All collaborators in one request therefore observe the same
 * workspace even when the underlying persistence state changes or becomes
 * temporarily unavailable later in the request.</p>
 */
@Component
public class WorkspaceResolver {

    private static final String REQUEST_CONTEXT_ATTRIBUTE =
            WorkspaceResolver.class.getName() + ".resolvedContext";

    private final WorkspaceContextResolver contextResolver;

    public WorkspaceResolver(WorkspaceContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    /**
     * Resolve the current user's username from the security context.
     *
     * @return the authenticated username, or the configured default user when
     *         no authenticated principal exists
     */
    public String resolveCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return WorkspaceManager.DEFAULT_USER;
    }

    /**
     * Resolve the full workspace context for the current request. Resolver
     * failures propagate; this method never manufactures a shared fallback.
     *
     * @return the stable active workspace context for this request
     */
    public WorkspaceContext resolveCurrentContext() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object cached = attributes.getAttribute(
                    REQUEST_CONTEXT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof WorkspaceContext context) {
                return context;
            }
        }

        WorkspaceContext resolved = contextResolver.resolveCurrentContext();
        if (resolved == null) {
            throw new IllegalStateException("Workspace context resolver returned null");
        }
        if (attributes != null) {
            attributes.setAttribute(
                    REQUEST_CONTEXT_ATTRIBUTE,
                    resolved,
                    RequestAttributes.SCOPE_REQUEST);
        }
        return resolved;
    }
}
