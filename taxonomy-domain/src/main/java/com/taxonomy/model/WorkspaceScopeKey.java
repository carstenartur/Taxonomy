package com.taxonomy.model;

/**
 * Stable workspace-overlay identity shared by branch-local projections.
 *
 * <p>This identity deliberately represents only the optional workspace overlay:
 * central state uses {@link #SHARED}, while workspace state uses the normalized
 * workspace identifier. It is not the repository/workspace/branch routing key
 * used by journals and interoperability persistence.</p>
 */
public final class WorkspaceScopeKey {

    public static final String SHARED = "__shared__";

    private WorkspaceScopeKey() {
    }

    public static String forWorkspace(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return SHARED;
        }
        return workspaceId.strip();
    }
}
