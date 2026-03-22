package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProjection;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.repository.WorkspaceProjectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages per-user workspace projection state with persistent storage.
 *
 * <p>A projection is a materialized view of the architecture DSL at a specific
 * commit. This service wraps the existing in-memory projection tracking in
 * {@link UserWorkspaceState} with a persistent {@link WorkspaceProjection}
 * entity, ensuring projection metadata survives application restarts.
 *
 * <p>The service provides methods to record materializations, check staleness
 * relative to the current Git HEAD, and retrieve projection details for API
 * responses.
 */
@Service
public class WorkspaceProjectionService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceProjectionService.class);

    private final WorkspaceProjectionRepository projectionRepository;
    private final WorkspaceManager workspaceManager;
    private final DslGitRepositoryFactory repositoryFactory;
    private final UserWorkspaceRepository workspaceRepository;

    public WorkspaceProjectionService(WorkspaceProjectionRepository projectionRepository,
                                      WorkspaceManager workspaceManager,
                                      DslGitRepositoryFactory repositoryFactory,
                                      UserWorkspaceRepository workspaceRepository) {
        this.projectionRepository = projectionRepository;
        this.workspaceManager = workspaceManager;
        this.repositoryFactory = repositoryFactory;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Resolve the Git repository for the given workspace context.
     *
     * @param ctx the workspace context (use {@link WorkspaceContext#SHARED}
     *            for the system repository)
     * @return the resolved DslGitRepository
     */
    private DslGitRepository resolveRepository(WorkspaceContext ctx) {
        return repositoryFactory.resolveRepository(ctx);
    }

    /**
     * Get or create the persistent projection record for a user.
     *
     * <p>If no projection exists, a new record is created with default values
     * and linked to the user's workspace. If persistence fails, the error
     * is logged and an unsaved instance is returned as a best-effort fallback.
     *
     * @param username the authenticated user's username
     * @return the projection entity (never null)
     */
    public WorkspaceProjection getOrCreateProjection(String username) {
        return projectionRepository.findByUsername(username)
                .orElseGet(() -> createProjection(username));
    }

    private WorkspaceProjection createProjection(String username) {
        log.info("Creating projection record for user '{}'", username);
        WorkspaceProjection projection = new WorkspaceProjection();
        projection.setUsername(username);
        // Link to existing UserWorkspace if available; otherwise generate a new ID
        String wsId = workspaceRepository.findByUsernameAndSharedFalse(username)
                .map(UserWorkspace::getWorkspaceId)
                .orElseGet(() -> UUID.randomUUID().toString());
        projection.setWorkspaceId(wsId);
        projection.setStale(false);
        projection.setCreatedAt(Instant.now());
        try {
            return projectionRepository.save(projection);
        } catch (Exception e) {
            // Non-fatal: in-memory state still works; subsequent calls will
            // retry persistence via the orElseGet path.
            log.warn("Could not persist projection for user '{}': {}",
                    username, e.getMessage());
            return projection;
        }
    }

    /**
     * Record that a materialization completed successfully for a user.
     *
     * <p>Updates both the persistent projection record and the in-memory
     * workspace state so that staleness checks are consistent.
     *
     * @param username the user whose projection was materialized
     * @param commitId the commit SHA that was materialized
     * @param branch   the branch that was materialized
     */
    public void recordProjection(String username, String commitId, String branch) {
        try {
            WorkspaceProjection projection = getOrCreateProjection(username);
            projection.setProjectionCommitId(commitId);
            projection.setProjectionBranch(branch);
            projection.setProjectionTimestamp(Instant.now());
            projection.setStale(false);
            projection.setUpdatedAt(Instant.now());
            projectionRepository.save(projection);
            log.info("User '{}': recorded projection: branch='{}', commit='{}'",
                    username, branch, abbreviateSha(commitId));
        } catch (Exception e) {
            log.warn("Could not record projection for user '{}': {}", username, e.getMessage());
        }

        // Also update in-memory state
        UserWorkspaceState ws = workspaceManager.getOrCreateWorkspace(username);
        ws.recordProjection(commitId, branch);
    }

    /**
     * Record that a search index rebuild completed for a user.
     *
     * <p>Updates both the persistent projection record and the in-memory
     * workspace state.
     *
     * @param username the user whose index was rebuilt
     * @param commitId the commit SHA the index was built from
     */
    public void recordIndexBuild(String username, String commitId) {
        try {
            WorkspaceProjection projection = getOrCreateProjection(username);
            projection.setIndexCommitId(commitId);
            projection.setIndexTimestamp(Instant.now());
            projection.setUpdatedAt(Instant.now());
            projectionRepository.save(projection);
            log.info("User '{}': recorded index build: commit='{}'",
                    username, abbreviateSha(commitId));
        } catch (Exception e) {
            log.warn("Could not record index build for user '{}': {}", username, e.getMessage());
        }

        // Also update in-memory state
        UserWorkspaceState ws = workspaceManager.getOrCreateWorkspace(username);
        ws.recordIndexBuild(commitId);
    }

    /**
     * Check if the projection is stale relative to the HEAD of the given branch.
     *
     * <p>Uses the system repository (SHARED context). Use
     * {@link #isProjectionStale(String, String, WorkspaceContext)} for workspace-aware resolution.
     *
     * @param username the user to check
     * @param branch   the branch to check against
     * @return true if the projection needs to be rebuilt
     */
    public boolean isProjectionStale(String username, String branch) {
        return isProjectionStale(username, branch, WorkspaceContext.SHARED);
    }

    /**
     * Check if the projection is stale relative to the HEAD of the given branch.
     *
     * <p>A projection is stale when its recorded commit differs from the
     * current HEAD of the branch, meaning the user is viewing outdated data.
     * Both in-memory and persisted projection state are checked.
     *
     * @param username the user to check
     * @param branch   the branch to check against
     * @param ctx      the workspace context for repository resolution
     * @return true if the projection needs to be rebuilt
     */
    public boolean isProjectionStale(String username, String branch, WorkspaceContext ctx) {
        UserWorkspaceState ws = workspaceManager.getOrCreateWorkspace(username);
        String projCommit = ws.getLastProjectionCommit();

        // Also check persistent record for a more recent commit
        WorkspaceProjection projection = null;
        try {
            projection = getOrCreateProjection(username);
            String persistedCommit = projection.getProjectionCommitId();
            if (persistedCommit != null) {
                projCommit = persistedCommit;
            }
        } catch (Exception e) {
            log.warn("Could not check persistent projection for user '{}': {}",
                    username, e.getMessage());
        }

        if (projCommit == null) {
            return false;
        }

        // Compare against actual branch HEAD
        try {
            String headCommit = resolveRepository(ctx).getHeadCommit(branch);
            if (headCommit == null) {
                return false;
            }
            boolean stale = !headCommit.equals(projCommit);
            // Persist the computed stale flag back to the entity
            if (projection != null && projection.isStale() != stale) {
                projection.setStale(stale);
                projection.setUpdatedAt(Instant.now());
                projectionRepository.save(projection);
            }
            return stale;
        } catch (IOException e) {
            log.warn("Could not resolve HEAD for branch '{}': {}", branch, e.getMessage());
            return false;
        }
    }

    /**
     * Return a map with projection details suitable for API responses.
     *
     * <p>Combines persistent and in-memory state to provide a comprehensive
     * view of the user's projection status.
     *
     * @param username the user whose projection info to retrieve
     * @return a map containing projection metadata
     */
    public Map<String, Object> getProjectionInfo(String username) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("username", username);

        UserWorkspaceState ws = workspaceManager.getOrCreateWorkspace(username);
        info.put("lastProjectionCommit", ws.getLastProjectionCommit());
        info.put("lastProjectionBranch", ws.getLastProjectionBranch());
        info.put("lastProjectionTimestamp", ws.getLastProjectionTimestamp());
        info.put("lastIndexCommit", ws.getLastIndexCommit());
        info.put("lastIndexTimestamp", ws.getLastIndexTimestamp());

        try {
            WorkspaceProjection projection = getOrCreateProjection(username);
            info.put("persistedProjectionCommit", projection.getProjectionCommitId());
            info.put("persistedProjectionBranch", projection.getProjectionBranch());
            info.put("persistedProjectionTimestamp", projection.getProjectionTimestamp());
            info.put("persistedIndexCommit", projection.getIndexCommitId());
            info.put("persistedIndexTimestamp", projection.getIndexTimestamp());
            info.put("stale", projection.isStale());
        } catch (Exception e) {
            log.warn("Could not read persistent projection for user '{}': {}",
                    username, e.getMessage());
            info.put("persistenceError", e.getMessage());
        }

        return info;
    }

    // ── Internal helpers ────────────────────────────────────────────

    private String abbreviateSha(String commitId) {
        if (commitId == null) return "null";
        return commitId.substring(0, Math.min(7, commitId.length()));
    }
}
