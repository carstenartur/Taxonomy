package com.taxonomy.workspace.service;

/**
 * Immutable value object representing the current workspace context for a user.
 *
 * <p>Carries the {@code username}, {@code workspaceId}, and {@code currentBranch}
 * needed to scope data operations (relations, hypotheses, proposals, commit search)
 * to the active workspace.
 *
 * <p>A special {@link #SHARED} instance represents the system-wide / legacy scope
 * where no per-user isolation applies. Its {@code workspaceId} is {@code null},
 * which maps directly to the {@code workspace_id IS NULL} condition in OR-null
 * queries — ensuring that SHARED callers see all legacy/shared data without
 * accidental workspace filtering.
 *
 * @param username      the authenticated user's username
 * @param workspaceId   the unique workspace identifier (from {@link com.taxonomy.workspace.model.UserWorkspace}),
 *                      or {@code null} for the shared / legacy scope
 * @param currentBranch the Git branch the user is currently working on
 */
public record WorkspaceContext(
        String username,
        String workspaceId,
        String currentBranch
) {
    /**
     * Shared / legacy context — used when no per-user workspace is active.
     *
     * <p>{@code workspaceId} is {@code null} so that downstream services skip
     * workspace filtering and return all data (shared + legacy). The
     * {@code currentBranch} is set to the conventional default; callers that
     * need the configurable shared branch should resolve it via
     * {@link SystemRepositoryService#getSharedBranch()}.
     */
    public static final WorkspaceContext SHARED =
            new WorkspaceContext("system", null, "draft");
}
