package com.taxonomy.workspace.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the current username plus request-bound workspace and repository contexts.
 *
 * <p>Successfully resolved contexts are cached for the lifetime of the HTTP request.
 * All collaborators in one request therefore observe the same logical repository
 * even when active-workspace metadata changes concurrently.</p>
 */
@Component
public class WorkspaceResolver {

    private static final String REQUEST_CONTEXT_ATTRIBUTE =
            WorkspaceResolver.class.getName() + ".resolvedContext";
    private static final String REQUEST_REPOSITORY_CONTEXT_ATTRIBUTE =
            WorkspaceResolver.class.getName() + ".resolvedRepositoryContext";

    private final WorkspaceContextResolver contextResolver;

    public WorkspaceResolver(WorkspaceContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    /** Resolve the authenticated username or the configured default user. */
    public String resolveCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return WorkspaceManager.DEFAULT_USER;
    }

    /**
     * Resolve the legacy workspace context for the current request.
     * Resolver failures propagate; this method never manufactures a fallback.
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
        cache(attributes, REQUEST_CONTEXT_ATTRIBUTE, resolved);
        return resolved;
    }

    /**
     * Resolve the mandatory logical repository identity for the current request.
     * A central context always carries a repository ID; it never reuses the
     * ambiguous legacy {@link WorkspaceContext#SHARED} sentinel.
     */
    public RepositoryContext resolveCurrentRepositoryContext() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            Object cached = attributes.getAttribute(
                    REQUEST_REPOSITORY_CONTEXT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof RepositoryContext context) {
                return context;
            }
        }

        RepositoryContext resolved = contextResolver.resolveRepositoryContextForUser(
                resolveCurrentUsername());
        if (resolved == null) {
            throw new IllegalStateException("Repository context resolver returned null");
        }
        cache(attributes, REQUEST_REPOSITORY_CONTEXT_ATTRIBUTE, resolved);
        return resolved;
    }

    private static void cache(
            RequestAttributes attributes, String attributeName, Object value) {
        if (attributes != null) {
            attributes.setAttribute(
                    attributeName, value, RequestAttributes.SCOPE_REQUEST);
        }
    }
}
