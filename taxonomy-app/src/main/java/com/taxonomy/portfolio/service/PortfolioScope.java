package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import com.taxonomy.workspace.service.WorkspaceContext;

import java.util.Locale;

/** Normalizes the request-bound repository/workspace/branch into a stable persistence scope. */
public final class PortfolioScope {

    public static final String CENTRAL_SCOPE = PortfolioTenantIdentity.CENTRAL_SCOPE;
    private static final String WORKSPACE_PREFIX =
            PortfolioTenantIdentity.WORKSPACE_SCOPE_PREFIX;

    private PortfolioScope() {
    }

    public static String key(String username, WorkspaceContext context) {
        return identity(username, context).scopeKey();
    }

    public static PortfolioTenantIdentity identity(
            String username,
            WorkspaceContext context) {
        if (context == null) {
            throw new IllegalArgumentException("workspace context is required");
        }
        String repositoryId = requireText(context.repositoryId(), "repositoryId");
        String workspaceScope = workspaceId(context) != null
                ? WORKSPACE_PREFIX + workspaceId(context)
                : CENTRAL_SCOPE;
        return new PortfolioTenantIdentity(repositoryId, workspaceScope, branch(context));
    }

    public static String username(String username, WorkspaceContext context) {
        return normalizedUsername(username, context);
    }

    public static String workspaceId(WorkspaceContext context) {
        return context != null && context.workspaceId() != null && !context.workspaceId().isBlank()
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
        if (context == null) {
            throw new IllegalArgumentException("workspace context is required");
        }
        return requireText(context.repositoryId(), "repositoryId");
    }

    private static String normalizedUsername(String username, WorkspaceContext context) {
        String candidate = username;
        if ((candidate == null || candidate.isBlank()) && context != null) {
            candidate = context.username();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = "anonymous";
        }
        return candidate.strip().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.strip();
    }
}
