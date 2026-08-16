package com.taxonomy.workspace.service;

/**
 * Immutable compatibility view of the current workspace context for a user.
 *
 * <p>Productive request handling enriches this value with the exact logical
 * {@code repositoryId} through {@link WorkspaceResolver}. Repository-sensitive
 * code should prefer {@link RepositoryContext}; this record remains for callers
 * whose public service signatures have not yet been migrated.</p>
 *
 * @param username      the authenticated user's username
 * @param workspaceId   the selected workspace identifier, or {@code null} for a central scope
 * @param currentBranch the exact selected Git branch
 * @param repositoryId  the exact selected logical repository
 */
public record WorkspaceContext(
        String username,
        String workspaceId,
        String currentBranch,
        String repositoryId
) {
    /**
     * Stable compatibility identity used only by direct legacy constructors,
     * predominantly focused tests outside a request. Productive HTTP requests
     * replace it with the catalog repository ID in {@link WorkspaceResolver}.
     */
    public static final String LEGACY_REPOSITORY_ID = "legacy-primary";

    /** Source-compatible constructor retained while legacy signatures are migrated. */
    public WorkspaceContext(String username, String workspaceId, String currentBranch) {
        this(username, workspaceId, currentBranch, LEGACY_REPOSITORY_ID);
    }

    public WorkspaceContext {
        if (repositoryId == null || repositoryId.isBlank()) {
            throw new IllegalArgumentException("repositoryId must not be blank");
        }
    }

    /** Shared compatibility context for non-request callers. */
    public static final WorkspaceContext SHARED =
            new WorkspaceContext("system", null, "draft", LEGACY_REPOSITORY_ID);
}
