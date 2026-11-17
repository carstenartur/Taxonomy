package com.taxonomy.composition.architecture;

import com.taxonomy.model.WorkspaceOverlayScope;

/** Resolves source links written before the workspace ID was recorded alongside its display key. */
final class AnalysisSourceWorkspace {
    private AnalysisSourceWorkspace() {}

    static String resolve(String scopeKey, String workspaceId) {
        if (workspaceId == null)
            return WorkspaceOverlayScope.CENTRAL_SCOPE_KEY.equals(scopeKey) ? null : scopeKey;
        if (!workspaceId.equals(workspaceId.strip()) || !WorkspaceOverlayScope.keyFor(workspaceId).equals(scopeKey))
            throw new IllegalArgumentException("Source workspace ID and scope key disagree");
        return workspaceId.isEmpty() ? null : workspaceId;
    }
}
