package com.taxonomy.workspace.service;

/**
 * Explicit routing identity for every repository-sensitive operation.
 *
 * <p>The repository ID is mandatory. A {@code null} workspace ID addresses the
 * selected central repository; a non-null workspace ID addresses an isolated
 * working copy derived from that repository.</p>
 */
public record RepositoryContext(
        String repositoryId,
        String workspaceId,
        String branch,
        String username,
        RepositoryScope scope) {

    public RepositoryContext {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be blank");
        }
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException("branch must not be blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (scope == RepositoryScope.WORKSPACE
                && (workspaceId == null || workspaceId.isBlank())) {
            throw new IllegalArgumentException("workspaceId is required for WORKSPACE scope");
        }
    }

    public static RepositoryContext centralRead(
            String repositoryId, String branch, String username) {
        return new RepositoryContext(
                repositoryId, null, branch, username, RepositoryScope.CENTRAL_READ);
    }

    public static RepositoryContext workspace(
            String repositoryId, String workspaceId, String branch, String username) {
        return new RepositoryContext(
                repositoryId, workspaceId, branch, username, RepositoryScope.WORKSPACE);
    }
}
