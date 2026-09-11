package com.taxonomy.model;

/**
 * Stable identity for a central or workspace-local projection overlay.
 *
 * <p>Central state shares one stable scope key. Workspace state uses the normalized
 * workspace ID. This intentionally does not encode repository or branch routing;
 * callers that need journal/repository identity must use their repository-context key.</p>
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
