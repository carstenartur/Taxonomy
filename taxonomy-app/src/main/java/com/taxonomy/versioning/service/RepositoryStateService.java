package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslCommit;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ProjectionState;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.dto.ViewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.UserWorkspaceState;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;

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
 *
 * <p>All mutable state (projection tracking, operation tracking) is isolated
 * per user via {@link WorkspaceManager}. Overloaded methods without a
 * {@code username} parameter use {@link WorkspaceManager#DEFAULT_USER}
 * for backward compatibility with tests and unauthenticated callers.
 */
@Service
public class RepositoryStateService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryStateService.class);

    private final DslGitRepositoryFactory repositoryFactory;
    private final WorkspaceManager workspaceManager;
    private final SystemRepositoryService systemRepositoryService;

    public RepositoryStateService(DslGitRepositoryFactory repositoryFactory,
                                  WorkspaceManager workspaceManager,
                                  SystemRepositoryService systemRepositoryService) {
        this.repositoryFactory = repositoryFactory;
        this.workspaceManager = workspaceManager;
        this.systemRepositoryService = systemRepositoryService;
    }

    /**
     * Resolve the Git repository for the given workspace context.
     *
     * <p>Callers pass an explicit {@link WorkspaceContext} rather than relying
     * on {@code resolveCurrentContext()} — this keeps the service free from
     * implicit request-scoped state and makes it testable without a
     * SecurityContext.
     *
     * @param ctx the workspace context (use {@link WorkspaceContext#SHARED}
     *            for the system repository)
     * @return the resolved DslGitRepository
     */
    private DslGitRepository resolveRepository(WorkspaceContext ctx) {
        return repositoryFactory.resolveRepository(ctx);
    }

    // ── Workspace-aware methods ─────────────────────────────────────

    /**
     * Build the full repository state snapshot for a user and branch.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #getState(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param username the user whose workspace state to include
     * @param branch   the branch to query (e.g. "draft")
     * @return the full repository state
     */
    public RepositoryState getState(String username, String branch) {
        return getState(username, branch, WorkspaceContext.SHARED);
    }

    /**
     * Build the full repository state snapshot for a user, branch, and workspace context.
     *
     * @param username the user whose workspace state to include
     * @param branch   the branch to query (e.g. "draft")
     * @param ctx      the workspace context for repository resolution
     * @return the full repository state
     */
    public RepositoryState getState(String username, String branch, WorkspaceContext ctx) {
        UserWorkspaceState ws = resolveState(username);
        DslGitRepository repo = resolveRepository(ctx);
        try {
            DslCommit headInfo = repo.getHeadCommitInfo(branch);
            List<String> branches = repo.getBranchNames();
            int commitCount = repo.getCommitCount(branch);
            String headCommit = headInfo != null ? headInfo.commitId() : null;

            boolean projStale = isProjectionStaleForCommit(ws, headCommit);
            boolean idxStale = isIndexStale(ws, headCommit);

            return new RepositoryState(
                    branch,
                    headCommit,
                    headInfo != null ? headInfo.timestamp() : null,
                    headInfo != null ? headInfo.author() : null,
                    headInfo != null ? headInfo.message() : null,
                    branches,
                    ws.isOperationInProgress(),
                    ws.getOperationKind(),
                    ws.getLastProjectionCommit(),
                    ws.getLastProjectionBranch(),
                    ws.getLastProjectionTimestamp(),
                    projStale,
                    ws.getLastIndexCommit(),
                    idxStale,
                    commitCount,
                    repo.isDatabaseBacked()
            );
        } catch (IOException e) {
            log.error("Failed to build repository state for user '{}', branch '{}'", username, branch, e);
            return new RepositoryState(
                    branch, null, null, null, null, List.of(),
                    false, null, null, null, null, false, null, false, 0,
                    repo.isDatabaseBacked()
            );
        }
    }

    /**
     * Build a {@link ViewContext} for inclusion in API responses.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #getViewContext(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param username the user whose workspace state to include
     * @param branch   the branch the data is based on
     * @return the view context metadata
     */
    public ViewContext getViewContext(String username, String branch) {
        return getViewContext(username, branch, WorkspaceContext.SHARED);
    }

    /**
     * Build a {@link ViewContext} for inclusion in API responses.
     *
     * @param username the user whose workspace state to include
     * @param branch   the branch the data is based on
     * @param ctx      the workspace context for repository resolution
     * @return the view context metadata
     */
    public ViewContext getViewContext(String username, String branch, WorkspaceContext ctx) {
        UserWorkspaceState ws = resolveState(username);
        DslGitRepository repo = resolveRepository(ctx);
        try {
            DslCommit headInfo = repo.getHeadCommitInfo(branch);
            String headCommit = headInfo != null ? headInfo.commitId() : null;

            return new ViewContext(
                    headCommit,
                    branch,
                    headInfo != null ? headInfo.timestamp() : null,
                    true,
                    isProjectionStaleForCommit(ws, headCommit),
                    isIndexStale(ws, headCommit)
            );
        } catch (IOException e) {
            log.error("Failed to build view context for user '{}', branch '{}'", username, branch, e);
            return new ViewContext(null, branch, null, true, false, false);
        }
    }

    /**
     * Record that a materialization completed successfully for a user's workspace.
     *
     * @param username the user whose projection to record
     * @param commitId the commit SHA that was materialized
     * @param branch   the branch that was materialized
     */
    public void recordProjection(String username, String commitId, String branch) {
        UserWorkspaceState ws = resolveState(username);
        ws.recordProjection(commitId, branch);
        log.info("User '{}': recorded projection: branch='{}', commit='{}'",
                username, branch, abbreviateSha(commitId));
    }

    /**
     * Record that a search index rebuild completed for a user's workspace.
     *
     * @param username the user whose index to record
     * @param commitId the commit SHA the index was built from
     */
    public void recordIndexBuild(String username, String commitId) {
        UserWorkspaceState ws = resolveState(username);
        ws.recordIndexBuild(commitId);
        log.info("User '{}': recorded index build: commit='{}'",
                username, abbreviateSha(commitId));
    }

    /**
     * Check if the DB projection is stale for a user's workspace.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #isProjectionStale(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param username the user to check
     * @param branch   the branch to check
     * @return true if projection is stale
     */
    public boolean isProjectionStale(String username, String branch) {
        return isProjectionStale(username, branch, WorkspaceContext.SHARED);
    }

    /**
     * Check if the DB projection is stale for a user's workspace.
     *
     * @param username the user to check
     * @param branch   the branch to check
     * @param ctx      the workspace context for repository resolution
     * @return true if projection is stale
     */
    public boolean isProjectionStale(String username, String branch, WorkspaceContext ctx) {
        UserWorkspaceState ws = resolveState(username);
        try {
            String headCommit = resolveRepository(ctx).getHeadCommit(branch);
            return isProjectionStaleForCommit(ws, headCommit);
        } catch (IOException e) {
            log.error("Failed to check projection staleness for user '{}', branch '{}'",
                    username, branch, e);
            return false;
        }
    }

    /**
     * Get the full projection state for a user's workspace.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #getProjectionState(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param username the user to check
     * @param branch   the branch to check against
     * @return the projection state
     */
    public ProjectionState getProjectionState(String username, String branch) {
        return getProjectionState(username, branch, WorkspaceContext.SHARED);
    }

    /**
     * Get the full projection state for a user's workspace.
     *
     * @param username the user to check
     * @param branch   the branch to check against
     * @param ctx      the workspace context for repository resolution
     * @return the projection state
     */
    public ProjectionState getProjectionState(String username, String branch, WorkspaceContext ctx) {
        UserWorkspaceState ws = resolveState(username);
        try {
            String headCommit = resolveRepository(ctx).getHeadCommit(branch);
            return new ProjectionState(
                    ws.getLastProjectionCommit(),
                    ws.getLastProjectionBranch(),
                    ws.getLastProjectionTimestamp(),
                    ws.getLastIndexCommit(),
                    ws.getLastIndexTimestamp(),
                    isProjectionStaleForCommit(ws, headCommit),
                    isIndexStale(ws, headCommit)
            );
        } catch (IOException e) {
            log.error("Failed to get projection state for user '{}', branch '{}'",
                    username, branch, e);
            return new ProjectionState(
                    ws.getLastProjectionCommit(), ws.getLastProjectionBranch(),
                    ws.getLastProjectionTimestamp(),
                    ws.getLastIndexCommit(), ws.getLastIndexTimestamp(), false, false
            );
        }
    }

    /**
     * Mark the start of a multi-step operation for a user's workspace.
     *
     * @param username the user performing the operation
     * @param kind     the operation kind ("merge", "cherry-pick", "revert")
     */
    public void beginOperation(String username, String kind) {
        resolveState(username).beginOperation(kind);
        log.info("User '{}': operation started: {}", username, kind);
    }

    /**
     * Mark the end of a multi-step operation for a user's workspace.
     *
     * @param username the user whose operation ended
     */
    public void endOperation(String username) {
        UserWorkspaceState ws = resolveState(username);
        log.info("User '{}': operation ended: {}", username, ws.getOperationKind());
        ws.endOperation();
    }

    // ── Internal helpers ────────────────────────────────────────────

    /**
     * Resolve the workspace branch for a user. Falls back to the configured
     * shared branch (via {@link SystemRepositoryService#getSharedBranch()})
     * if the user does not have a specific workspace branch configured.
     *
     * @param username the username to resolve the branch for
     * @return the user's active branch, or the shared branch as default
     */
    public String resolveWorkspaceBranch(String username) {
        WorkspaceInfo info = workspaceManager.getWorkspaceInfo(username);
        if (info != null && info.currentBranch() != null) {
            return info.currentBranch();
        }
        return systemRepositoryService.getSharedBranch();
    }

    private UserWorkspaceState resolveState(String username) {
        return workspaceManager.getOrCreateWorkspace(username);
    }

    /**
     * Ensure that the workspace state for the given user is provisioned.
     *
     * <p>This triggers lazy workspace creation (via
     * {@link com.taxonomy.workspace.service.WorkspaceManager#getOrCreateWorkspace})
     * so that subsequent calls to
     * {@link com.taxonomy.workspace.service.WorkspaceContextResolver#resolveForUser}
     * find the workspace and return a workspace-scoped context instead of
     * {@link com.taxonomy.workspace.service.WorkspaceContext#SHARED}.
     *
     * @param username the authenticated user's username
     */
    public void ensureWorkspaceState(String username) {
        resolveState(username);
    }

    private boolean isProjectionStaleForCommit(UserWorkspaceState ws, String headCommit) {
        String projCommit = ws.getLastProjectionCommit();
        if (headCommit == null || projCommit == null) {
            return false;
        }
        return !headCommit.equals(projCommit);
    }

    private boolean isIndexStale(UserWorkspaceState ws, String headCommit) {
        String idxCommit = ws.getLastIndexCommit();
        if (headCommit == null || idxCommit == null) {
            return false;
        }
        return !headCommit.equals(idxCommit);
    }

    private String abbreviateSha(String commitId) {
        if (commitId == null) return "null";
        return commitId.substring(0, Math.min(7, commitId.length()));
    }
}
