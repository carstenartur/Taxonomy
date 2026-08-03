package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.UserWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Resolves the current {@link WorkspaceContext} from the security context
 * and the persistent workspace metadata.
 *
 * <p>Authenticated users are isolated by default. A shared context is returned
 * only for the configured default/system user or when the operator has
 * explicitly enabled shared workspace mode. Missing or inconsistent workspace
 * metadata therefore fails closed in multi-user deployments.</p>
 */
@Service
public class WorkspaceContextResolver {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextResolver.class);

    private final WorkspaceManager workspaceManager;
    private final SystemRepositoryService systemRepositoryService;
    private final boolean sharedModeEnabled;

    public WorkspaceContextResolver(WorkspaceManager workspaceManager,
                                    SystemRepositoryService systemRepositoryService,
                                    @Value("${taxonomy.workspace.shared-mode-enabled:true}")
                                    boolean sharedModeEnabled) {
        this.workspaceManager = workspaceManager;
        this.systemRepositoryService = systemRepositoryService;
        this.sharedModeEnabled = sharedModeEnabled;
    }

    /** Resolve the workspace context for the currently authenticated user. */
    public WorkspaceContext resolveCurrentContext() {
        return resolveForUser(resolveUsername());
    }

    /**
     * Resolve the workspace context for a specific user.
     *
     * @return an isolated context, or the explicit shared context when shared
     *         mode is enabled
     * @throws IllegalStateException when an authenticated user has no isolated
     *         workspace and shared mode is disabled
     */
    public WorkspaceContext resolveForUser(String username) {
        if (username == null || username.isBlank()
                || WorkspaceManager.DEFAULT_USER.equals(username)) {
            return WorkspaceContext.SHARED;
        }

        UserWorkspace workspace = workspaceManager.findActiveWorkspace(username);
        if (workspace == null) {
            workspace = workspaceManager.findUserWorkspace(username);
        }

        if (workspace != null && workspace.getWorkspaceId() != null
                && !workspace.getWorkspaceId().isBlank()) {
            String branch = workspace.getCurrentBranch() != null
                    && !workspace.getCurrentBranch().isBlank()
                    ? workspace.getCurrentBranch()
                    : systemRepositoryService.getSharedBranch();
            log.debug("Resolved workspace context for user '{}': workspace={}, branch={}",
                    username, workspace.getWorkspaceId(), branch);
            return new WorkspaceContext(username, workspace.getWorkspaceId(), branch);
        }

        if (sharedModeEnabled) {
            log.warn("Explicit shared workspace mode is active for user '{}'; no data isolation applies",
                    username);
            return WorkspaceContext.SHARED;
        }

        throw new IllegalStateException(
                "No isolated workspace is provisioned for authenticated user '" + username + "'");
    }

    private String resolveUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return authentication.getName();
        }
        return WorkspaceManager.DEFAULT_USER;
    }
}
