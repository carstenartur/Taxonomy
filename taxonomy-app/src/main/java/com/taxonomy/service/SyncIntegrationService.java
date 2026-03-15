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
     * Count unpublished commits on the user's branch since the last sync.
     *
     * <p>Uses the last synced commit as a reference point. If both branches
     * share the same HEAD, there are no local changes. Otherwise, counts
     * the difference in total commits as an approximation. This heuristic
     * works best when branches diverge linearly from the same ancestor.
     *
     * @param username   the authenticated user's username
     * @param userBranch the user's branch to check
     * @return the number of unpublished commits (approximate)
     * @throws IOException if Git operations fail
     */
    public int getLocalChanges(String username, String userBranch) throws IOException {
        String userHead = gitRepository.getHeadCommit(userBranch);
        String sharedHead = gitRepository.getHeadCommit(SHARED_BRANCH);

        // If both HEADs are the same, no local changes
        if (userHead != null && userHead.equals(sharedHead)) {
            updateUnpublishedCount(username, 0, "UP_TO_DATE");
            return 0;
        }

        // Approximate: compare commit counts on each branch
        int userCommits = gitRepository.getCommitCount(userBranch);
        int sharedCommits = gitRepository.getCommitCount(SHARED_BRANCH);
        int localChanges = Math.max(0, userCommits - sharedCommits);

        String status = localChanges > 0 ? "AHEAD" : "UP_TO_DATE";
        updateUnpublishedCount(username, localChanges, status);

        return localChanges;
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

    private String abbreviateSha(String commitId) {
        if (commitId == null) return "null";
        return commitId.substring(0, Math.min(7, commitId.length()));
    }
}
