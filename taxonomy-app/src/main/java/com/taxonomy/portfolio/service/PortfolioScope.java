package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTenantIdentity;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceScope;

/** Portfolio compatibility facade over the canonical workspace tenant scope. */
public final class PortfolioScope {

    public static final String CENTRAL_SCOPE = RepositoryTenantIdentity.CENTRAL_SCOPE;

    private PortfolioScope() {
    }

    public static String key(String username, WorkspaceContext context) {
        return WorkspaceScope.key(username, context);
    }

    public static PortfolioTenantIdentity identity(
            String username,
            WorkspaceContext context) {
        RepositoryTenantIdentity identity = WorkspaceScope.identity(context);
        return new PortfolioTenantIdentity(
                identity.repositoryId(), identity.workspaceScope(), identity.branch());
    }

    public static String username(String username, WorkspaceContext context) {
        return WorkspaceScope.username(username, context);
    }

    public static String workspaceId(WorkspaceContext context) {
        return WorkspaceScope.workspaceId(context);
    }

    public static String branch(WorkspaceContext context) {
        return WorkspaceScope.branch(context);
    }

    public static String repositoryId(WorkspaceContext context) {
        return WorkspaceScope.repositoryId(context);
    }
}
