package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves the current {@link WorkspaceContext} from the security context
 * and the persistent workspace metadata.
 *
 * <p>Combines the username (from Spring Security) with the
 * workspace metadata (from {@link WorkspaceManager}) to produce a
 * consistent context value that downstream services can use for
 * data isolation (workspace-scoped relations, hypotheses, proposals, etc.).
 *
 * <p>Falls back to {@link WorkspaceContext#SHARED} when no provisioned
 * workspace exists for the current user.
 */
@Service
public class WorkspaceContextResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextResolver.class);

    private final WorkspaceManager workspaceManager;
    private final SystemRepositoryService systemRepositoryService;

    public WorkspaceContextResolver(WorkspaceManager workspaceManager,
                                     SystemRepositoryService systemRepositoryService) {
        this.workspaceManager = workspaceManager;
        this.systemRepositoryService = systemRepositoryService;
    }

    /**
     * Resolve the workspace context for the currently authenticated user.
     *
     * @return the active workspace context (never {@code null})
     */
    public WorkspaceContext resolveCurrentContext() {
        String username = resolveUsername();
        return resolveForUser(username);
    }

    /**
     * Resolve the workspace context for a specific user.
     *
     * <p>Only users with an explicitly provisioned persistent workspace
     * receive a workspace-scoped context. Users with only a default
     * in-memory workspace state receive the {@link WorkspaceContext#SHARED}
     * fallback (backward-compatible, no data isolation).
     *
     * @param username the username to resolve context for
     * @return the workspace context (never {@code null})
     */
    public WorkspaceContext resolveForUser(String username) {
        if (username == null || username.isBlank()
                || WorkspaceManager.DEFAULT_USER.equals(username)) {
            return WorkspaceContext.SHARED;
        }

        // Try active workspace first (multi-workspace aware)
        UserWorkspace ws = workspaceManager.findActiveWorkspace(username);
        if (ws == null) {
            // Fall back to legacy single-workspace lookup for backward compatibility
            ws = workspaceManager.findUserWorkspace(username);
        }

        if (ws != null && ws.getWorkspaceId() != null) {
            String branch = ws.getCurrentBranch() != null
                    ? ws.getCurrentBranch()
                    : systemRepositoryService.getSharedBranch();
            log.debug("Resolved workspace context for user '{}': workspace={}, branch={}",
                    username, ws.getWorkspaceId(), branch);
            return new WorkspaceContext(username, ws.getWorkspaceId(), branch);
        }

        log.debug("No provisioned workspace for user '{}'; falling back to SHARED", username);
        return WorkspaceContext.SHARED;
    }

    private String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            return auth.getName();
        }
        return WorkspaceManager.DEFAULT_USER;
    }
}
