package com.taxonomy.service;

import com.taxonomy.dto.ProjectionState;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
     * Check whether a write operation is safe for the default user.
     *
     * @param branch        the target branch
     * @param operationType the type of operation
     * @return the operation check result
     */
    public OperationCheck checkWriteOperation(String branch, String operationType) {
        return checkWriteOperation(WorkspaceManager.DEFAULT_USER, branch, operationType);
    }

    /**
     * Check whether a write operation is safe for a specific user.
     *
     * @param username      the user attempting the operation
     * @param branch        the target branch
     * @param operationType the type of operation (e.g. "commit", "materialize", "cherry-pick", "merge")
     * @return the operation check result
     */
    public OperationCheck checkWriteOperation(String username, String branch, String operationType) {
        List<String> warnings = new ArrayList<>();
        List<String> blocks = new ArrayList<>();

        // Block if current context is read-only (per-user check)
        if (contextNavigationService != null && contextNavigationService.isReadOnly(username)) {
            blocks.add("Current context is read-only. Switch to an editable context before making changes.");
        }

        var state = stateService.getState(username, branch);

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
        ProjectionState ps = stateService.getProjectionState(username, branch);
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
}
