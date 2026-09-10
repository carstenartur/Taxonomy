package com.taxonomy.workspace.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Explicit routing identity for every repository-sensitive operation.
 *
 * <p>The repository ID, branch, username and scope are mandatory. Central and
 * fork contexts have no workspace ID; a workspace context must identify the
 * isolated working copy derived from the selected repository.</p>
 */
public record RepositoryContext(
        String repositoryId,
        String workspaceId,
        String branch,
        String username,
        RepositoryScope scope) {

    public RepositoryContext {
        repositoryId = requireText(repositoryId, "repositoryId");
        branch = requireText(branch, "branch");
        username = requireText(username, "username");
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (workspaceId != null) {
            workspaceId = requireText(workspaceId, "workspaceId");
        }
        if (scope == RepositoryScope.WORKSPACE && workspaceId == null) {
            throw new IllegalArgumentException("workspaceId is required for WORKSPACE scope");
        }
        if (scope != RepositoryScope.WORKSPACE && workspaceId != null) {
            throw new IllegalArgumentException(
                    "workspaceId must be absent outside WORKSPACE scope");
        }
    }

    public static RepositoryContext centralRead(
            String repositoryId, String branch, String username) {
        return new RepositoryContext(
                repositoryId, null, branch, username, RepositoryScope.CENTRAL_READ);
    }

    public static RepositoryContext centralWrite(
            String repositoryId, String branch, String username) {
        return new RepositoryContext(
                repositoryId, null, branch, username, RepositoryScope.CENTRAL_WRITE);
    }

    public static RepositoryContext workspace(
            String repositoryId, String workspaceId, String branch, String username) {
        return new RepositoryContext(
                repositoryId, workspaceId, branch, username, RepositoryScope.WORKSPACE);
    }

    /**
     * Stable storage and protocol identity for this repository/workspace/branch tuple.
     * The actor is deliberately excluded so authorized users address the same workspace state.
     *
     * <p>The representation is compatibility-sensitive because it is already persisted by the
     * editor and interoperability journals.</p>
     */
    public String workspaceScopeKey() {
        return sha256(repositoryId + "\u0000" + workspaceId + "\u0000" + branch);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
