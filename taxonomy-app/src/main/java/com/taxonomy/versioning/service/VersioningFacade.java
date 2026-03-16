package com.taxonomy.versioning.service;

import com.taxonomy.dto.ContextComparison;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.versioning.model.ContextHistoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * High-level facade that aggregates the versioning domain services.
 *
 * <p>This class provides coarse-grained operations that coordinate
 * {@link ContextNavigationService}, {@link ContextCompareService},
 * {@link ContextHistoryService}, and {@link RepositoryStateService}
 * into workflows that controllers and other consumers can call
 * without orchestrating the individual services themselves.
 */
@Service
public class VersioningFacade {

    private static final Logger log = LoggerFactory.getLogger(VersioningFacade.class);

    private final ContextNavigationService navigationService;
    private final ContextCompareService compareService;
    private final ContextHistoryService historyService;
    private final RepositoryStateService stateService;

    public VersioningFacade(ContextNavigationService navigationService,
                            ContextCompareService compareService,
                            ContextHistoryService historyService,
                            RepositoryStateService stateService) {
        this.navigationService = navigationService;
        this.compareService = compareService;
        this.historyService = historyService;
        this.stateService = stateService;
    }

    // ── Composite result records ────────────────────────────────────

    /**
     * Combines a newly created variant context with a snapshot of the
     * repository state on that variant branch.
     *
     * @param variantContext   the context on the new variant branch
     * @param repositoryState the repository state for the variant branch
     */
    public record VariantPreview(
            ContextRef variantContext,
            RepositoryState repositoryState
    ) {}

    /**
     * Combines the user's current navigation context, repository state,
     * and recent persistent navigation history.
     *
     * @param currentContext   the user's active context reference
     * @param repositoryState the repository state for the active branch
     * @param history         the user's recent navigation history (newest first)
     */
    public record FullContextState(
            ContextRef currentContext,
            RepositoryState repositoryState,
            List<ContextHistoryRecord> history
    ) {}

    // ── High-level operations ───────────────────────────────────────

    /**
     * Create a new branch variant from the user's current context and
     * return a preview that includes the variant context and the
     * repository state on the new branch.
     *
     * @param username    the user creating the variant
     * @param variantName the name for the new branch
     * @return the variant context paired with the repository state
     * @throws IOException if the branch creation fails
     */
    public VariantPreview createVariantWithPreview(String username,
                                                   String variantName) throws IOException {
        ContextRef variant = navigationService.createVariantFromCurrent(username, variantName);
        RepositoryState repoState = stateService.getState(username, variant.branch());
        log.info("User '{}': created variant '{}' with preview", username, variantName);
        return new VariantPreview(variant, repoState);
    }

    /**
     * Compare two branches at their HEAD commits and return a semantic
     * comparison summary.
     *
     * @param leftBranch  the left (source/older) branch name
     * @param rightBranch the right (target/newer) branch name
     * @return the comparison result including diff summary and semantic changes
     * @throws IOException if Git operations fail
     */
    @Transactional(readOnly = true)
    public ContextComparison compareAndSummarize(String leftBranch,
                                                 String rightBranch) throws IOException {
        ContextRef left = branchRef(leftBranch);
        ContextRef right = branchRef(rightBranch);
        log.debug("Comparing branches: '{}' vs '{}'", leftBranch, rightBranch);
        return compareService.compareBranches(left, right);
    }

    /**
     * Build a complete picture of a user's current versioning state:
     * the active navigation context, the repository state on that branch,
     * and the user's recent persistent navigation history.
     *
     * @param username the user whose state to retrieve
     * @return the combined context, repository, and history state
     */
    @Transactional(readOnly = true)
    public FullContextState getFullContextState(String username) {
        ContextRef current = navigationService.getCurrentContext(username);
        RepositoryState repoState = stateService.getState(username, current.branch());
        List<ContextHistoryRecord> history = historyService.getHistory(username);
        log.debug("User '{}': assembled full context state on branch '{}'",
                username, current.branch());
        return new FullContextState(current, repoState, history);
    }

    // ── Internal helpers ────────────────────────────────────────────

    /**
     * Build a minimal {@link ContextRef} that identifies a branch without
     * a specific commit (HEAD is implied).
     *
     * <p>Most fields are {@code null} because
     * {@link ContextCompareService#compareBranches} only reads
     * {@link ContextRef#branch()} — the remaining fields are unused.
     */
    private static ContextRef branchRef(String branch) {
        return new ContextRef(
                null, branch, null, Instant.now(),
                ContextMode.READ_ONLY,
                null, null, null, null, null, false
        );
    }
}
