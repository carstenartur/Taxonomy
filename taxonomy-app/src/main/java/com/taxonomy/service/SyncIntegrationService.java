package com.taxonomy.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.model.SyncState;
import com.taxonomy.model.UserWorkspace;
import com.taxonomy.repository.SyncStateRepository;
import com.taxonomy.repository.UserWorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Manages synchronization between user workspaces and the shared integration
 * repository.
 *
 * <p>The shared branch (called {@code "draft"} by default) represents the
 * canonical team-wide state. Users work on their own branches and periodically
 * sync (pull) from the shared branch and publish (push) their changes back.
 *
 * <p>This service tracks the synchronization state via the {@link SyncState}
 * entity and delegates actual Git merge operations to {@link DslGitRepository}.
 */
@Service
public class SyncIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(SyncIntegrationService.class);

    /** The default shared integration branch name. */
    private static final String SHARED_BRANCH = "draft";

    private final SyncStateRepository syncStateRepository;
    private final DslGitRepository gitRepository;
    private final UserWorkspaceRepository workspaceRepository;

    public SyncIntegrationService(SyncStateRepository syncStateRepository,
                                  DslGitRepository gitRepository,
                                  UserWorkspaceRepository workspaceRepository) {
        this.syncStateRepository = syncStateRepository;
        this.gitRepository = gitRepository;
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Get or create the sync state for a user.
     *
     * <p>If no sync state record exists, a new one is created with default
     * values and an {@code UP_TO_DATE} status. If persistence fails, the
     * error is logged and an unsaved instance is returned as a best-effort
     * fallback.
     *
     * @param username the authenticated user's username
     * @return the sync state entity (never null)
     */
    public SyncState getSyncState(String username) {
        return syncStateRepository.findByUsername(username)
                .orElseGet(() -> createSyncState(username));
    }

    private SyncState createSyncState(String username) {
        log.info("Creating sync state for user '{}'", username);
        SyncState state = new SyncState();
        state.setUsername(username);
        // Link to existing UserWorkspace if available; otherwise generate a new ID
        String wsId = workspaceRepository.findByUsernameAndSharedFalse(username)
                .map(UserWorkspace::getWorkspaceId)
                .orElseGet(() -> UUID.randomUUID().toString());
        state.setWorkspaceId(wsId);
        state.setSyncStatus("UP_TO_DATE");
        state.setUnpublishedCommitCount(0);
        state.setCreatedAt(Instant.now());
        try {
            return syncStateRepository.save(state);
        } catch (Exception e) {
            // Non-fatal: sync state can be recreated; subsequent calls
            // will retry persistence via the orElseGet path.
            log.warn("Could not persist sync state for user '{}': {}",
                    username, e.getMessage());
            return state;
        }
    }

    /**
     * Merge the shared branch into the user's branch (pull).
     *
     * <p>This brings the user's branch up to date with the latest shared
     * state. After a successful merge, the sync state is updated to record
     * the shared branch HEAD as the last synced commit.
     *
     * @param username   the authenticated user's username
     * @param userBranch the user's branch to merge into
     * @return the merge commit SHA
     * @throws IOException if the Git merge operation fails
     */
    public String syncFromShared(String username, String userBranch) throws IOException {
        log.info("User '{}': syncing from shared branch '{}' into '{}'",
                username, SHARED_BRANCH, userBranch);

        String mergeCommit = gitRepository.merge(SHARED_BRANCH, userBranch);

        if (mergeCommit == null) {
            log.warn("User '{}': merge from '{}' into '{}' returned null (branch missing or conflict)",
                    username, SHARED_BRANCH, userBranch);
            throw new IOException("Sync failed: merge returned null (branch missing or conflict)");
        }

        // Update sync state
        try {
            SyncState state = getSyncState(username);
            String sharedHead = gitRepository.getHeadCommit(SHARED_BRANCH);
            state.setLastSyncedCommitId(sharedHead);
            state.setLastSyncTimestamp(Instant.now());
            state.setSyncStatus("UP_TO_DATE");
            state.setUpdatedAt(Instant.now());
            syncStateRepository.save(state);
        } catch (Exception e) {
            log.warn("Could not update sync state after pull for user '{}': {}",
                    username, e.getMessage());
        }

        log.info("User '{}': sync from shared complete, merge commit='{}'",
                username, abbreviateSha(mergeCommit));
        return mergeCommit;
    }

    /**
     * Merge the user's branch into the shared branch (publish).
     *
     * <p>This publishes the user's changes to the shared integration
     * repository. After a successful merge, the sync state is updated to
     * record the published commit and reset the unpublished count.
     *
     * @param username   the authenticated user's username
     * @param userBranch the user's branch to merge from
     * @return the merge commit SHA on the shared branch
     * @throws IOException if the Git merge operation fails
     */
    public String publishToShared(String username, String userBranch) throws IOException {
        log.info("User '{}': publishing branch '{}' to shared branch '{}'",
                username, userBranch, SHARED_BRANCH);

        String mergeCommit = gitRepository.merge(userBranch, SHARED_BRANCH);

        if (mergeCommit == null) {
            log.warn("User '{}': merge from '{}' into '{}' returned null (branch missing or conflict)",
                    username, userBranch, SHARED_BRANCH);
            throw new IOException("Publish failed: merge returned null (branch missing or conflict)");
        }

        // Update sync state
        try {
            SyncState state = getSyncState(username);
            state.setLastPublishedCommitId(mergeCommit);
            state.setLastPublishTimestamp(Instant.now());
            state.setSyncStatus("UP_TO_DATE");
            state.setUnpublishedCommitCount(0);
            state.setUpdatedAt(Instant.now());
            syncStateRepository.save(state);
        } catch (Exception e) {
            log.warn("Could not update sync state after publish for user '{}': {}",
                    username, e.getMessage());
        }

        log.info("User '{}': publish to shared complete, merge commit='{}'",
                username, abbreviateSha(mergeCommit));
        return mergeCommit;
    }

    /**
     * Count unpublished commits on the user's branch relative to the shared branch.
     *
     * <p>Uses merge-base computation to accurately determine ahead/behind counts.
     * The sync status is set to:
     * <ul>
     *   <li>{@code UP_TO_DATE} — both branches are at the same commit</li>
     *   <li>{@code AHEAD} — user has commits not in the shared branch</li>
     *   <li>{@code BEHIND} — shared branch has commits the user hasn't pulled</li>
     *   <li>{@code DIVERGED} — both sides have unique commits</li>
     * </ul>
     *
     * @param username   the authenticated user's username
     * @param userBranch the user's branch to check
     * @return the number of unpublished commits (ahead count)
     * @throws IOException if Git operations fail
     */
    public int getLocalChanges(String username, String userBranch) throws IOException {
        int[] aheadBehind = gitRepository.getAheadBehindCounts(userBranch, SHARED_BRANCH);
        int ahead = aheadBehind[0];
        int behind = aheadBehind[1];

        String status;
        if (ahead == 0 && behind == 0) {
            status = "UP_TO_DATE";
        } else if (ahead > 0 && behind == 0) {
            status = "AHEAD";
        } else if (ahead == 0 && behind > 0) {
            status = "BEHIND";
        } else {
            status = "DIVERGED";
        }

        updateUnpublishedCount(username, ahead, status);
        return ahead;
    }

    private void updateUnpublishedCount(String username, int count, String status) {
        try {
            SyncState state = getSyncState(username);
            state.setUnpublishedCommitCount(count);
            state.setSyncStatus(status);
            state.setUpdatedAt(Instant.now());
            syncStateRepository.save(state);
        } catch (Exception e) {
            log.warn("Could not update unpublished count for user '{}': {}",
                    username, e.getMessage());
        }
    }

    /**
     * Check if the workspace has unpublished changes.
     *
     * <p>Reads the persistent sync state to determine if the user has
     * local commits that have not been published to the shared branch.
     *
     * @param username the authenticated user's username
     * @return true if the workspace has unpublished changes
     */
    public boolean isDirty(String username) {
        try {
            SyncState state = getSyncState(username);
            return state.getUnpublishedCommitCount() > 0
                    || "AHEAD".equals(state.getSyncStatus())
                    || "DIVERGED".equals(state.getSyncStatus());
        } catch (Exception e) {
            log.warn("Could not check dirty state for user '{}': {}", username, e.getMessage());
            return false;
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    /**
     * Resolution strategy for a diverged sync state.
     */
    public enum DivergedStrategy {
        /** Merge the shared branch into the user's branch. */
        MERGE,
        /** Keep the user's branch, discard shared changes. */
        KEEP_MINE,
        /** Replace the user's branch with the shared branch. */
        TAKE_SHARED
    }

    /**
     * Resolve a diverged state between user's branch and the shared branch.
     *
     * <p>Three strategies are available:
     * <ul>
     *   <li>{@code MERGE} — merge shared into user branch (may fail if conflicts persist)</li>
     *   <li>{@code KEEP_MINE} — force-update shared from user (publish forcefully)</li>
     *   <li>{@code TAKE_SHARED} — force-update user from shared (overwrite local changes)</li>
     * </ul>
     *
     * @param username   the authenticated user's username
     * @param userBranch the user's branch
     * @param strategy   the resolution strategy
     * @return a description of what was done
     * @throws IOException if a Git operation fails
     */
    public String resolveDiverged(String username, String userBranch, DivergedStrategy strategy)
            throws IOException {
        log.info("User '{}': resolving DIVERGED state with strategy {} on branch '{}'",
                username, strategy, userBranch);

        switch (strategy) {
            case MERGE:
                String mergeCommit = gitRepository.merge(SHARED_BRANCH, userBranch);
                if (mergeCommit == null) {
                    throw new IOException("Merge failed — conflict could not be auto-resolved. " +
                            "Try KEEP_MINE or TAKE_SHARED strategy instead.");
                }
                updateSyncStateAfterResolve(username, "UP_TO_DATE");
                return "Merged shared into your branch: " + abbreviateSha(mergeCommit);

            case KEEP_MINE:
                // Force publish: merge user → shared
                String publishCommit = gitRepository.merge(userBranch, SHARED_BRANCH);
                if (publishCommit == null) {
                    // If merge fails, restore from user's HEAD
                    String userHead = gitRepository.getHeadCommit(userBranch);
                    publishCommit = gitRepository.restore(userHead, SHARED_BRANCH);
                    if (publishCommit == null) {
                        throw new IOException("Could not force-publish user branch to shared");
                    }
                }
                updateSyncStateAfterResolve(username, "UP_TO_DATE");
                return "Published your changes to shared: " + abbreviateSha(publishCommit);

            case TAKE_SHARED:
                // Force sync: restore user branch from shared HEAD
                String sharedHead = gitRepository.getHeadCommit(SHARED_BRANCH);
                if (sharedHead == null) {
                    throw new IOException("Shared branch has no commits");
                }
                String restoreCommit = gitRepository.restore(sharedHead, userBranch);
                if (restoreCommit == null) {
                    throw new IOException("Could not restore user branch from shared");
                }
                updateSyncStateAfterResolve(username, "UP_TO_DATE");
                return "Replaced your branch with shared content: " + abbreviateSha(restoreCommit);

            default:
                throw new IllegalArgumentException("Unknown strategy: " + strategy);
        }
    }

    private void updateSyncStateAfterResolve(String username, String status) {
        try {
            SyncState state = getSyncState(username);
            state.setSyncStatus(status);
            state.setUnpublishedCommitCount(0);
            state.setLastSyncTimestamp(Instant.now());
            state.setUpdatedAt(Instant.now());
            syncStateRepository.save(state);
        } catch (Exception e) {
            log.warn("Could not update sync state after resolve for user '{}': {}",
                    username, e.getMessage());
        }
    }

    // ── Internal helpers (private) ──────────────────────────────────

    private String abbreviateSha(String commitId) {
        if (commitId == null) return "null";
        return commitId.substring(0, Math.min(7, commitId.length()));
    }
}
