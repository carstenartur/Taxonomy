package com.taxonomy.workspace.service;

/**
 * Immutable value object representing the current workspace context for a user.
 *
 * <p>Carries the {@code username}, {@code workspaceId}, and {@code currentBranch}
 * needed to scope data operations (relations, hypotheses, proposals, commit search)
 * to the active workspace.
 *
 * <p>A special {@link #SHARED} instance represents the system-wide / legacy scope
 * where no per-user isolation applies.
 *
 * @param username      the authenticated user's username
 * @param workspaceId   the unique workspace identifier (from {@link com.taxonomy.workspace.model.UserWorkspace})
 * @param currentBranch the Git branch the user is currently working on
 */
public record WorkspaceContext(
        String username,
        String workspaceId,
        String currentBranch
) {
    /** Shared / legacy context — used when no per-user workspace is active. */
    public static final WorkspaceContext SHARED =
            new WorkspaceContext("system", "shared", "draft");
}
