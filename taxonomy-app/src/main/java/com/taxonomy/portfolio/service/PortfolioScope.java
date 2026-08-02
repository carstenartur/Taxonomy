package com.taxonomy.portfolio.service;

import com.taxonomy.workspace.service.WorkspaceContext;

import java.util.Locale;

/** Normalizes the request-bound workspace context into a stable persistence scope. */
public final class PortfolioScope {

    private PortfolioScope() {
    }

    public static String key(String username, WorkspaceContext context) {
        if (context != null && context.workspaceId() != null && !context.workspaceId().isBlank()) {
            return "workspace:" + context.workspaceId().strip().toLowerCase(Locale.ROOT);
        }
        return "user:" + normalizedUsername(username, context);
    }

    public static String username(String username, WorkspaceContext context) {
        return normalizedUsername(username, context);
    }

    public static String workspaceId(WorkspaceContext context) {
        return context != null && context.workspaceId() != null && !context.workspaceId().isBlank()
                ? context.workspaceId().strip() : null;
    }

    public static String branch(WorkspaceContext context) {
        return context != null && context.currentBranch() != null && !context.currentBranch().isBlank()
                ? context.currentBranch().strip() : "draft";
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
}
