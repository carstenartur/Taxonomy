package com.taxonomy.workspace.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the current workspace username from the Spring Security context.
 *
 * <p>Used by controllers and services to determine which user's workspace
 * to operate on without requiring explicit username parameters. Falls back
 * to {@link WorkspaceManager#DEFAULT_USER} when no authentication is present
 * (e.g. in tests or unauthenticated endpoints).
 */
@Component
public class WorkspaceResolver {

    /**
     * Resolve the current user's username from the security context.
     *
     * @return the authenticated username, or "anonymous" if not authenticated
     */
    public String resolveCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return WorkspaceManager.DEFAULT_USER;
    }
}
