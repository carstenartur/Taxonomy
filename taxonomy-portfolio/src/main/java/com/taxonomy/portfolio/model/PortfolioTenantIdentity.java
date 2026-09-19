package com.taxonomy.portfolio.model;

import com.taxonomy.workspace.model.RepositoryTenantIdentity;

/**
 * Portfolio-facing compatibility identity for the shared repository/workspace/
 * branch tenant encoding.
 *
 * <p>The canonical encoder belongs to the workspace boundary because the same
 * exact tenant scope is also used by ad-hoc analysis drafts and other persisted
 * workspace data. Keeping this type preserves the portfolio API without making
 * analysis depend on the portfolio domain.</p>
 */
public record PortfolioTenantIdentity(
        String repositoryId,
        String workspaceScope,
        String branch
) {
    public static final String PREFIX = RepositoryTenantIdentity.PREFIX;
    public static final String CENTRAL_SCOPE = RepositoryTenantIdentity.CENTRAL_SCOPE;
    public static final String WORKSPACE_SCOPE_PREFIX =
            RepositoryTenantIdentity.WORKSPACE_SCOPE_PREFIX;
    public static final int MAX_SCOPE_KEY_LENGTH =
            RepositoryTenantIdentity.MAX_SCOPE_KEY_LENGTH;

    public PortfolioTenantIdentity {
        RepositoryTenantIdentity normalized = new RepositoryTenantIdentity(
                repositoryId, workspaceScope, branch);
        repositoryId = normalized.repositoryId();
        workspaceScope = normalized.workspaceScope();
        branch = normalized.branch();
    }

    public String scopeKey() {
        return new RepositoryTenantIdentity(
                repositoryId, workspaceScope, branch).scopeKey();
    }

    public static boolean isEncoded(String value) {
        return RepositoryTenantIdentity.isEncoded(value);
    }

    public static PortfolioTenantIdentity parse(String scopeKey) {
        RepositoryTenantIdentity identity = RepositoryTenantIdentity.parse(scopeKey);
        return new PortfolioTenantIdentity(
                identity.repositoryId(), identity.workspaceScope(), identity.branch());
    }
}
