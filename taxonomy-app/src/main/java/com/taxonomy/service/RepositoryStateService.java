package com.taxonomy.service;

import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dto.ProjectionState;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.dto.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Provides a unified view of the Git repository state, including projection
 * and search-index freshness tracking.
 *
 * <p>This service is the single source of truth for answering: "What version
 * of the architecture is the user looking at?" It tracks:
 * <ul>
 *   <li>Git HEAD — the latest commit on a branch</li>
 *   <li>Projection commit — the commit the DB projection was built from</li>
 *   <li>Index commit — the commit the search index was built from</li>
 *   <li>Operation state — whether a multi-step merge/cherry-pick is in progress</li>
 * </ul>
 */
@Service
public class RepositoryStateService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryStateService.class);

    private final DslGitRepository gitRepository;

    // Tracked state — updated by DslMaterializeService after each materialization
    private volatile String lastProjectionCommit;
    private volatile String lastProjectionBranch;
    private volatile Instant lastProjectionTimestamp;
    private volatile String lastIndexCommit;
    private volatile Instant lastIndexTimestamp;

    // Operation tracking (for multi-step merge/cherry-pick UI)
    private volatile String operationKind;  // null = no operation

    public RepositoryStateService(DslGitRepository gitRepository) {
        this.gitRepository = gitRepository;
    }

    /**
     * Build the full repository state snapshot for a branch.
     *
     * @param branch the branch to query (e.g. "draft")
     * @return the full repository state
     */
    public RepositoryState getState(String branch) {
        try {
            DslCommit headInfo = gitRepository.getHeadCommitInfo(branch);
            List<String> branches = gitRepository.getBranchNames();
            int commitCount = gitRepository.getCommitCount(branch);
            String headCommit = headInfo != null ? headInfo.commitId() : null;

            boolean projStale = isProjectionStaleForCommit(headCommit);
            boolean idxStale = isIndexStale(headCommit);

            return new RepositoryState(
                    branch,
                    headCommit,
                    headInfo != null ? headInfo.timestamp() : null,
                    headInfo != null ? headInfo.author() : null,
                    headInfo != null ? headInfo.message() : null,
                    branches,
                    operationKind != null,
                    operationKind,
                    lastProjectionCommit,
                    lastProjectionBranch,
                    lastProjectionTimestamp,
                    projStale,
                    lastIndexCommit,
                    idxStale,
                    commitCount,
                    gitRepository.isDatabaseBacked()
            );
        } catch (IOException e) {
            log.error("Failed to build repository state for branch '{}'", branch, e);
            return new RepositoryState(
                    branch, null, null, null, null, List.of(),
                    false, null, null, null, null, false, null, false, 0,
                    gitRepository.isDatabaseBacked()
            );
        }
    }

    /**
     * Build a {@link ViewContext} for inclusion in API responses.
     *
     * @param branch the branch the data is based on
     * @return the view context metadata
     */
    public ViewContext getViewContext(String branch) {
        try {
            DslCommit headInfo = gitRepository.getHeadCommitInfo(branch);
            String headCommit = headInfo != null ? headInfo.commitId() : null;

            return new ViewContext(
                    headCommit,
                    branch,
                    headInfo != null ? headInfo.timestamp() : null,
                    true, // architecture data always may include provisional relations
                    isProjectionStaleForCommit(headCommit),
                    isIndexStale(headCommit)
            );
        } catch (IOException e) {
            log.error("Failed to build view context for branch '{}'", branch, e);
            return new ViewContext(null, branch, null, true, false, false);
        }
    }

    /**
     * Record that a materialization completed successfully.
     *
     * <p>Called by {@code DslMaterializeService} after a successful materialization
     * to track which commit the DB projection is based on.
     *
     * @param commitId the commit SHA that was materialized
     * @param branch   the branch that was materialized
     */
    public void recordProjection(String commitId, String branch) {
        this.lastProjectionCommit = commitId;
        this.lastProjectionBranch = branch;
        this.lastProjectionTimestamp = Instant.now();
        log.info("Recorded projection: branch='{}', commit='{}'", branch,
                commitId != null ? commitId.substring(0, Math.min(7, commitId.length())) : "null");
    }

    /**
     * Record that a search index rebuild completed successfully.
     *
     * <p>Called by the search index infrastructure after a successful reindex.
     *
     * @param commitId the commit SHA the index was built from
     */
    public void recordIndexBuild(String commitId) {
        this.lastIndexCommit = commitId;
        this.lastIndexTimestamp = Instant.now();
        log.info("Recorded index build: commit='{}'",
                commitId != null ? commitId.substring(0, Math.min(7, commitId.length())) : "null");
    }

    /**
     * Check if the DB projection is stale (HEAD has moved past the projection commit).
     *
     * @param branch the branch to check
     * @return true if projection is stale
     */
    public boolean isProjectionStale(String branch) {
        try {
            String headCommit = gitRepository.getHeadCommit(branch);
            return isProjectionStaleForCommit(headCommit);
        } catch (IOException e) {
            log.error("Failed to check projection staleness for branch '{}'", branch, e);
            return false;
        }
    }

    /**
     * Get the full projection state for diagnostics.
     *
     * @param branch the branch to check against
     * @return the projection state
     */
    public ProjectionState getProjectionState(String branch) {
        try {
            String headCommit = gitRepository.getHeadCommit(branch);
            return new ProjectionState(
                    lastProjectionCommit,
                    lastProjectionBranch,
                    lastProjectionTimestamp,
                    lastIndexCommit,
                    lastIndexTimestamp,
                    isProjectionStaleForCommit(headCommit),
                    isIndexStale(headCommit)
            );
        } catch (IOException e) {
            log.error("Failed to get projection state for branch '{}'", branch, e);
            return new ProjectionState(
                    lastProjectionCommit, lastProjectionBranch, lastProjectionTimestamp,
                    lastIndexCommit, lastIndexTimestamp, false, false
            );
        }
    }

    /**
     * Mark the start of a multi-step operation (e.g. merge, cherry-pick).
     *
     * @param kind the operation kind ("merge", "cherry-pick", "revert")
     */
    public void beginOperation(String kind) {
        this.operationKind = kind;
        log.info("Operation started: {}", kind);
    }

    /**
     * Mark the end of a multi-step operation.
     */
    public void endOperation() {
        log.info("Operation ended: {}", operationKind);
        this.operationKind = null;
    }

    // ── Internal helpers ────────────────────────────────────────────

    private boolean isProjectionStaleForCommit(String headCommit) {
        if (headCommit == null || lastProjectionCommit == null) {
            return false; // no commit or no projection yet — not considered stale
        }
        return !headCommit.equals(lastProjectionCommit);
    }

    private boolean isIndexStale(String headCommit) {
        if (headCommit == null || lastIndexCommit == null) {
            return false;
        }
        return !headCommit.equals(lastIndexCommit);
    }
}
