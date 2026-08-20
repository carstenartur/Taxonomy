package com.taxonomy.workspace.service;

import com.taxonomy.workspace.model.RepositoryTenantIdentity;

import java.util.Locale;

/**
 * Normalizes a request-bound repository, workspace and branch into one stable
 * persistence scope shared by analyses, portfolios and future workspace data.
 */
public final class WorkspaceScope {

    private WorkspaceScope() {
    }

    public static String key(String username, WorkspaceContext context) {
        return identity(context).scopeKey();
    }

    public static RepositoryTenantIdentity identity(WorkspaceContext context) {
        if (context == null) {
            throw new IllegalArgumentException("workspace context is required");
        }
        String workspaceId = workspaceId(context);
        String workspaceScope = workspaceId != null
                ? RepositoryTenantIdentity.WORKSPACE_SCOPE_PREFIX + workspaceId
                : RepositoryTenantIdentity.CENTRAL_SCOPE;
        return new RepositoryTenantIdentity(
                repositoryId(context), workspaceScope, branch(context));
    }

    public static String username(String username, WorkspaceContext context) {
        String candidate = username;
        if ((candidate == null || candidate.isBlank()) && context != null) {
            candidate = context.username();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = WorkspaceManager.DEFAULT_USER;
        }
        return candidate.strip().toLowerCase(Locale.ROOT);
    }

    public static String workspaceId(WorkspaceContext context) {
        return context != null && context.workspaceId() != null
                && !context.workspaceId().isBlank()
                ? context.workspaceId().strip() : null;
    }

    public static String branch(WorkspaceContext context) {
        if (context == null || context.currentBranch() == null
                || context.currentBranch().isBlank()) {
            throw new IllegalArgumentException("currentBranch is required");
        }
        return context.currentBranch().strip();
    }

    public static String repositoryId(WorkspaceContext context) {
        if (context == null || context.repositoryId() == null
                || context.repositoryId().isBlank()) {
            throw new IllegalArgumentException("repositoryId is required");
        }
        return context.repositoryId().strip();
    }
}
