package com.taxonomy.workspace.service;

import org.springframework.security.access.AccessDeniedException;

/** Application identity authority for opt-in collaborative workspaces. */
@FunctionalInterface
public interface WorkspaceActorAccess {
    void requireActive(String username);

    static WorkspaceActorAccess unavailable() {
        return username -> { throw new AccessDeniedException("Verified active principal required for shared editing"); };
    }
}
