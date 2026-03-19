package com.taxonomy.dto;

import java.time.Instant;

/**
 * Summary information about a user workspace.
 *
 * <p>A workspace provides an isolated context for a user to work on the
 * architecture. Each workspace has its own branch, navigation history,
 * projection tracking, and operation state. The underlying Git repository
 * is shared, with branch-level isolation providing logical separation.
 *
 * @param workspaceId          unique identifier for this workspace
 * @param username             the user who owns this workspace
 * @param displayName          optional human-readable name
 * @param currentBranch        the branch this workspace is currently on
 * @param baseBranch           the branch this workspace was created from
 * @param shared               whether this is the shared integration workspace
 * @param currentContext       the active context reference (may be null)
 * @param createdAt            when the workspace was created
 * @param lastAccessedAt       when the workspace was last accessed
 * @param provisioningStatus   provisioning lifecycle state (e.g. READY, NOT_PROVISIONED)
 * @param topologyMode         repository topology mode (e.g. INTERNAL_SHARED)
 * @param sourceRepositoryId   the system repository this workspace is linked to (may be null)
 */
public record WorkspaceInfo(
    String workspaceId,
    String username,
    String displayName,
    String currentBranch,
    String baseBranch,
    boolean shared,
    ContextRef currentContext,
    Instant createdAt,
    Instant lastAccessedAt,
    String provisioningStatus,
    String topologyMode,
    String sourceRepositoryId
) {

    /**
     * Backward-compatible constructor for existing code that does not supply
     * provisioning, topology, or source-repository information.
     */
    public WorkspaceInfo(
            String workspaceId, String username, String displayName,
            String currentBranch, String baseBranch, boolean shared,
            ContextRef currentContext, Instant createdAt, Instant lastAccessedAt) {
        this(workspaceId, username, displayName, currentBranch, baseBranch, shared,
                currentContext, createdAt, lastAccessedAt, "READY", "INTERNAL_SHARED", null);
    }
}
