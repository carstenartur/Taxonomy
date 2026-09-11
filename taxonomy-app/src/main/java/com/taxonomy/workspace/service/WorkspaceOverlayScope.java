package com.taxonomy.workspace.service;

/**
 * Stable identity for the workspace overlay used by rebuildable architecture and knowledge projections.
 *
 * <p>This identity is deliberately narrower than {@link RepositoryContext#repositoryWorkspaceScopeKey()}:
 * central state uses one shared scope while a workspace uses its normalized workspace ID. It is therefore
 * suitable for projection isolation, but not for repository/workspace/branch journal routing.</p>
 */
public final class WorkspaceOverlayScope {

    public static final String CENTRAL_SCOPE_KEY = "__shared__";

    private WorkspaceOverlayScope() {
    }

    public static String keyFor(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return CENTRAL_SCOPE_KEY;
        }
        return workspaceId.strip();
    }
}
