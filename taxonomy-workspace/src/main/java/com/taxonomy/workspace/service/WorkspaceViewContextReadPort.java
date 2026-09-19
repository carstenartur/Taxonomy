package com.taxonomy.workspace.service;

import com.taxonomy.dto.ViewContext;

/** Read-only view provenance for callers that already captured their workspace context. */
public interface WorkspaceViewContextReadPort {
    String resolveWorkspaceBranch(String username);
    ViewContext getViewContext(String username, String branch, WorkspaceContext context);
}
