package com.taxonomy.workspace.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.model.SyncState;
import com.taxonomy.workspace.repository.SyncStateRepository;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SyncIntegrationService}.
 *
 * <p>Verifies workspace sync lifecycle: state creation, sync from shared,
 * publish to shared, and dirty detection. Uses a real in-memory Git
 * repository for actual merge operations. No Spring context required.
 */
class SyncIntegrationServiceTest {

    private DslGitRepository gitRepo;
    private SyncStateRepository syncStateRepo;
    private UserWorkspaceRepository workspaceRepo;
    private SystemRepositoryService systemRepositoryService;
    private SyncIntegrationService syncService;

    private static final String SAMPLE_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice";
            }
            """;

    @BeforeEach
    void setUp() {
        gitRepo = new DslGitRepository();
        workspaceRepo = mock(UserWorkspaceRepository.class);
        when(workspaceRepo.findByUsernameAndSharedFalse(anyString())).thenReturn(Optional.empty());
        syncStateRepo = mock(SyncStateRepository.class);
        systemRepositoryService = mock(SystemRepositoryService.class);
        when(systemRepositoryService.getSharedBranch()).thenReturn("draft");
        syncService = new SyncIntegrationService(
                syncStateRepo, gitRepo, workspaceRepo, systemRepositoryService);

        // Default: no existing sync state, save returns the argument
        when(syncStateRepo.findByUsername(anyString())).thenReturn(Optional.empty());
        when(syncStateRepo.save(any(SyncState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ── Sync state creation ─────────────────────────────────────────

    @Test
    void getSyncState_createsNewIfNotExists() {
        SyncState state = syncService.getSyncState("alice");

        assertNotNull(state);
        assertEquals("alice", state.getUsername());
        assertNotNull(state.getWorkspaceId());
        assertEquals("UP_TO_DATE", state.getSyncStatus());
        assertEquals(0, state.getUnpublishedCommitCount());
        assertNotNull(state.getCreatedAt());
        verify(syncStateRepo).save(any(SyncState.class));
    }

    @Test
    void getSyncState_returnsExistingState() {
        SyncState existing = new SyncState();
        existing.setUsername("alice");
        existing.setWorkspaceId("ws-existing");
        existing.setSyncStatus("AHEAD");
        existing.setUnpublishedCommitCount(3);

        when(syncStateRepo.findByUsername("alice")).thenReturn(Optional.of(existing));

        SyncState state = syncService.getSyncState("alice");

        assertSame(existing, state);
        assertEquals("AHEAD", state.getSyncStatus());
        assertEquals(3, state.getUnpublishedCommitCount());
        verify(syncStateRepo, never()).save(any());
    }

    // ── Sync from shared ────────────────────────────────────────────

    @Test
    void syncFromShared_updatesSyncState() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");
        gitRepo.createBranch("alice-branch", "draft");

        // Add a new commit to shared (draft) branch
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "shared update");

        String mergeCommit = syncService.syncFromShared("alice", "alice-branch");

        assertNotNull(mergeCommit);
        // Verify sync state was persisted with updated fields
        verify(syncStateRepo, atLeastOnce()).save(argThat(state -> {
            // Only match the post-sync save (not the initial creation save)
            if (state.getLastSyncedCommitId() == null) {
                return false;
            }
            assertEquals("UP_TO_DATE", state.getSyncStatus());
            assertNotNull(state.getLastSyncTimestamp());
            return true;
        }));
    }

    // ── Publish to shared ───────────────────────────────────────────

    @Test
    void publishToShared_updatesSyncState() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");
        gitRepo.createBranch("alice-branch", "draft");

        // Add a commit to user's branch
        gitRepo.commitDsl("alice-branch", SAMPLE_DSL, "alice", "user change");

        String mergeCommit = syncService.publishToShared("alice", "alice-branch");

        assertNotNull(mergeCommit);
        // Verify sync state was persisted with publish fields
        verify(syncStateRepo, atLeastOnce()).save(argThat(state -> {
            // Only match the post-publish save (not the initial creation save)
            if (state.getLastPublishedCommitId() == null) {
                return false;
            }
            assertEquals("UP_TO_DATE", state.getSyncStatus());
            assertEquals(0, state.getUnpublishedCommitCount());
            assertNotNull(state.getLastPublishTimestamp());
            return true;
        }));
    }

    // ── Local changes detection ─────────────────────────────────────

    @Test
    void getLocalChanges_returnsZeroWhenClean() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");
        gitRepo.createBranch("alice-branch", "draft");

        int changes = syncService.getLocalChanges("alice", "alice-branch");

        assertEquals(0, changes);
    }

    @Test
    void isDirty_returnsFalseWhenClean() {
        // New sync state defaults to UP_TO_DATE with 0 unpublished
        assertFalse(syncService.isDirty("alice"));
    }

    // ── Diverged resolution ─────────────────────────────────────────

    @Test
    void resolveDiverged_mergeStrategy() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");
        gitRepo.createBranch("alice-branch", "draft");
        // Create divergence: both sides have unique commits
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "shared change");
        gitRepo.commitDsl("alice-branch", SAMPLE_DSL, "alice", "user change");

        String result = syncService.resolveDiverged("alice", "alice-branch",
                SyncIntegrationService.DivergedStrategy.MERGE);

        assertNotNull(result);
        assertTrue(result.contains("Merged"));
    }

    @Test
    void resolveDiverged_takeSharedStrategy() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");
        gitRepo.createBranch("alice-branch", "draft");
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "shared change");
        gitRepo.commitDsl("alice-branch", SAMPLE_DSL, "alice", "user change");

        String result = syncService.resolveDiverged("alice", "alice-branch",
                SyncIntegrationService.DivergedStrategy.TAKE_SHARED);

        assertNotNull(result);
        assertTrue(result.contains("Replaced"));
    }

    @Test
    void resolveDiverged_keepMineStrategy() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");
        gitRepo.createBranch("alice-branch", "draft");
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "shared change");
        gitRepo.commitDsl("alice-branch", SAMPLE_DSL, "alice", "user change");

        String result = syncService.resolveDiverged("alice", "alice-branch",
                SyncIntegrationService.DivergedStrategy.KEEP_MINE);

        assertNotNull(result);
        assertTrue(result.contains("Published"));
    }

    @Test
    void missingMergeResultsFailWithoutRecordingSuccessfulSyncOrPublish() throws Exception {
        DslGitRepository repository = mock(DslGitRepository.class);
        SyncIntegrationService service = serviceWith(repository);
        when(repository.merge("draft", "alice-branch")).thenReturn(null);
        when(repository.merge("alice-branch", "draft")).thenReturn(null);

        assertThrows(IOException.class,
                () -> service.syncFromShared("alice", "alice-branch"));
        assertThrows(IOException.class,
                () -> service.publishToShared("alice", "alice-branch"));
        verify(syncStateRepo, never()).save(argThat(state ->
                state.getLastSyncedCommitId() != null || state.getLastPublishedCommitId() != null));
    }

    @Test
    void localChangeClassificationPersistsAheadBehindAndDivergedStates() throws Exception {
        DslGitRepository repository = mock(DslGitRepository.class);
        SyncIntegrationService service = serviceWith(repository);
        SyncState state = existingState("alice");
        when(syncStateRepo.findByUsername("alice")).thenReturn(Optional.of(state));
        when(repository.getAheadBehindCounts("alice-branch", "draft"))
                .thenReturn(new int[]{1, 0}, new int[]{0, 2}, new int[]{3, 4});

        assertEquals(1, service.getLocalChanges("alice", "alice-branch"));
        assertEquals("AHEAD", state.getSyncStatus());
        assertEquals(0, service.getLocalChanges("alice", "alice-branch"));
        assertEquals("BEHIND", state.getSyncStatus());
        assertEquals(3, service.getLocalChanges("alice", "alice-branch"));
        assertEquals("DIVERGED", state.getSyncStatus());
        assertEquals(3, state.getUnpublishedCommitCount());
    }

    @Test
    void dirtyStateRecognizesCountAheadAndDivergedAndFailsClosedOnReadError() {
        SyncState state = existingState("alice");
        when(syncStateRepo.findByUsername("alice")).thenReturn(Optional.of(state));

        state.setUnpublishedCommitCount(1);
        assertTrue(syncService.isDirty("alice"));
        state.setUnpublishedCommitCount(0);
        state.setSyncStatus("AHEAD");
        assertTrue(syncService.isDirty("alice"));
        state.setSyncStatus("DIVERGED");
        assertTrue(syncService.isDirty("alice"));
        when(syncStateRepo.findByUsername("alice"))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertFalse(syncService.isDirty("alice"));
    }

    @Test
    void keepMineRestoresUserHeadWhenOrdinaryPublishCannotMerge() throws Exception {
        DslGitRepository repository = mock(DslGitRepository.class);
        SyncIntegrationService service = serviceWith(repository);
        when(repository.merge("alice-branch", "draft")).thenReturn(null);
        when(repository.getHeadCommit("alice-branch")).thenReturn("user-head");
        when(repository.restore("user-head", "draft")).thenReturn("restored-head");

        String result = service.resolveDiverged(
                "alice", "alice-branch", SyncIntegrationService.DivergedStrategy.KEEP_MINE);

        assertEquals("Published your changes to shared: restore", result);
        verify(repository).restore("user-head", "draft");
    }

    @Test
    void divergedResolutionReportsUnresolvedMergeAndRestoreFailures() throws Exception {
        DslGitRepository repository = mock(DslGitRepository.class);
        SyncIntegrationService service = serviceWith(repository);
        when(repository.merge("draft", "alice-branch")).thenReturn(null);
        assertThrows(IOException.class, () -> service.resolveDiverged(
                "alice", "alice-branch", SyncIntegrationService.DivergedStrategy.MERGE));

        when(repository.merge("alice-branch", "draft")).thenReturn(null);
        when(repository.getHeadCommit("alice-branch")).thenReturn("user-head");
        when(repository.restore("user-head", "draft")).thenReturn(null);
        assertThrows(IOException.class, () -> service.resolveDiverged(
                "alice", "alice-branch", SyncIntegrationService.DivergedStrategy.KEEP_MINE));

        when(repository.getHeadCommit("draft")).thenReturn(null);
        assertThrows(IOException.class, () -> service.resolveDiverged(
                "alice", "alice-branch", SyncIntegrationService.DivergedStrategy.TAKE_SHARED));
        when(repository.getHeadCommit("draft")).thenReturn("shared-head");
        when(repository.restore("shared-head", "alice-branch")).thenReturn(null);
        assertThrows(IOException.class, () -> service.resolveDiverged(
                "alice", "alice-branch", SyncIntegrationService.DivergedStrategy.TAKE_SHARED));
    }

    @Test
    void stateCreationUsesPersistentWorkspaceAndSurvivesSaveFailure() {
        com.taxonomy.workspace.model.UserWorkspace workspace =
                new com.taxonomy.workspace.model.UserWorkspace();
        workspace.setWorkspaceId("workspace-17");
        when(workspaceRepo.findByUsernameAndSharedFalse("alice"))
                .thenReturn(Optional.of(workspace));
        when(syncStateRepo.save(any(SyncState.class)))
                .thenThrow(new IllegalStateException("write unavailable"));

        SyncState state = syncService.getSyncState("alice");

        assertEquals("workspace-17", state.getWorkspaceId());
        assertEquals("UP_TO_DATE", state.getSyncStatus());
    }

    private SyncIntegrationService serviceWith(DslGitRepository repository) {
        return new SyncIntegrationService(
                syncStateRepo, repository, workspaceRepo, systemRepositoryService);
    }

    private static SyncState existingState(String username) {
        SyncState state = new SyncState();
        state.setUsername(username);
        state.setWorkspaceId("workspace-1");
        state.setSyncStatus("UP_TO_DATE");
        return state;
    }
}
