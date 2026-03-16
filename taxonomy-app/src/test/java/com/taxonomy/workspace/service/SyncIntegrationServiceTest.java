package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
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
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        when(wsRepo.findByUsernameAndSharedFalse(anyString())).thenReturn(Optional.empty());
        syncStateRepo = mock(SyncStateRepository.class);
        syncService = new SyncIntegrationService(syncStateRepo, gitRepo, wsRepo);

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
}
