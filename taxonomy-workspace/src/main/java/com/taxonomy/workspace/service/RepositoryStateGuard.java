package com.taxonomy.workspace.service;

import com.taxonomy.dto.ProjectionState;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.versioning.service.RepositoryStateService;

/**
 * Guards write operations by checking the current repository state.
 *
 * <p>Before committing, materializing, cherry-picking, or merging, this
 * component checks whether the operation is safe and returns warnings
 * or blocks. Also blocks writes when the current context is read-only.
 *
 * <p>All checks are workspace-aware: each user's operation state and
 * read-only status are independent.
 */
@Component
public class RepositoryStateGuard {

    private final RepositoryStateService stateService;
    private final @Nullable ContextNavigationService contextNavigationService;

    public RepositoryStateGuard(RepositoryStateService stateService,
                                @Nullable ContextNavigationService contextNavigationService) {
        this.stateService = stateService;
        this.contextNavigationService = contextNavigationService;
    }

    /**
     * Result of a write-operation safety check.
     *
     * @param allowed  whether the operation may proceed
     * @param warnings non-fatal issues the user should be aware of
     * @param blocks   fatal issues that prevent the operation
     */
    public record OperationCheck(
            boolean allowed,
            List<String> warnings,
            List<String> blocks
    ) {}

    /**
     * Compatibility entry point for non-request callers that still identify only actor and branch.
     * Productive repository-sensitive callers should use the exact-context overload.
     */
    public OperationCheck checkWriteOperation(String username, String branch, String operationType) {
        return checkWriteOperation(
                new WorkspaceContext(username, null, branch, WorkspaceContext.LEGACY_REPOSITORY_ID),
                operationType);
    }

    /**
     * Check whether a write operation is safe in the exact repository/workspace context
     * selected for the request. Actor, branch and repository routing all come from this
     * single context; callers cannot supply contradictory parallel values.
     *
     * @param workspaceContext the exact repository/workspace context being mutated
     * @param operationType    the type of operation
     * @return the operation check result
     */
    public OperationCheck checkWriteOperation(
            WorkspaceContext workspaceContext,
            String operationType) {
        if (workspaceContext == null) {
            throw new IllegalArgumentException("workspaceContext must not be null");
        }
        String username = requireText(workspaceContext.username(), "workspaceContext.username");
        String branch = requireText(workspaceContext.currentBranch(), "workspaceContext.currentBranch");
        operationType = requireText(operationType, "operationType");
        List<String> warnings = new ArrayList<>();
        List<String> blocks = new ArrayList<>();

        // Block if current context is read-only (per-user check)
        if (contextNavigationService != null && contextNavigationService.isReadOnly(username)) {
            blocks.add("Current context is read-only. Switch to an editable context before making changes.");
        }

        var state = stateService.getState(username, branch, workspaceContext);

        // Block if an operation is already in progress (per-user check)
        if (state.operationInProgress()) {
            blocks.add("A " + state.operationKind() + " operation is already in progress. " +
                    "Complete or cancel it before starting a new operation.");
        }

        // Block if branch doesn't exist (for operations that require existing branch)
        if (!"commit".equals(operationType) && state.headCommit() == null) {
            blocks.add("Branch '" + branch + "' does not exist or has no commits.");
        }

        // Warn if projection is stale (per-user check)
        ProjectionState ps = stateService.getProjectionState(username, branch, workspaceContext);
        if (ps.projectionStale()) {
            warnings.add("DB projection is stale — it was built from a different commit than HEAD. " +
                    "Consider re-materializing before this operation.");
        }

        // Warn if index is stale (per-user check)
        if (ps.indexStale()) {
            warnings.add("Search index is stale — search results may not reflect latest changes.");
        }

        boolean allowed = blocks.isEmpty();
        return new OperationCheck(allowed, warnings, blocks);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
