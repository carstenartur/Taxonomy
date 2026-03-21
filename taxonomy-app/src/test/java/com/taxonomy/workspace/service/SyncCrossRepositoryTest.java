package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
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
 * Tests for cross-repository sync operations in {@link SyncIntegrationService}.
 *
 * <p>Verifies that publish and sync work across separate workspace and system
 * repositories when a {@link DslGitRepositoryFactory} is configured.
 */
class SyncCrossRepositoryTest {

    private DslGitRepositoryFactory factory;
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

    private static final String UPDATED_DSL = """
            meta {
              language: "taxdsl";
              version: "2.0";
              namespace: "test";
            }

            element CP-1023 type Capability {
              title: "Secure Voice v2";
            }
            """;

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null); // in-memory mode
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        when(wsRepo.findByUsernameAndSharedFalse(anyString())).thenReturn(Optional.empty());
        syncStateRepo = mock(SyncStateRepository.class);
        SystemRepositoryService sysRepoService = mock(SystemRepositoryService.class);
        when(sysRepoService.getSharedBranch()).thenReturn("draft");

        syncService = new SyncIntegrationService(
                syncStateRepo, wsRepo, sysRepoService, factory);

        when(syncStateRepo.findByUsername(anyString())).thenReturn(Optional.empty());
        when(syncStateRepo.save(any(SyncState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void publishFromWorkspaceToShared_copiesDslToSystemRepo() throws IOException {
        // Commit DSL in workspace repo
        DslGitRepository wsRepo = factory.getWorkspaceRepository("ws-1");
        wsRepo.commitDsl("main", SAMPLE_DSL, "alice", "initial workspace commit");

        // Also need content in system repo shared branch for sync state
        DslGitRepository sysRepo = factory.getSystemRepository();
        sysRepo.commitDsl("draft", "meta { language: \"taxdsl\"; }", "system", "init");

        // Publish from workspace to shared
        String commitId = syncService.publishFromWorkspaceToShared("alice", "ws-1");

        assertNotNull(commitId, "Publish should return a commit ID");

        // Verify the DSL is now in the system repo's shared branch
        String sharedDsl = sysRepo.getDslAtHead("draft");
        assertEquals(SAMPLE_DSL, sharedDsl,
                "System repo should contain the workspace's DSL after publish");
    }

    @Test
    void syncFromSharedToWorkspace_copiesDslToWorkspaceRepo() throws IOException {
        // Commit DSL in system repo
        DslGitRepository sysRepo = factory.getSystemRepository();
        sysRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial shared commit");

        // Sync from shared to workspace
        String commitId = syncService.syncFromSharedToWorkspace("bob", "ws-2");

        assertNotNull(commitId, "Sync should return a commit ID");

        // Verify the DSL is now in the workspace repo
        DslGitRepository wsRepo = factory.getWorkspaceRepository("ws-2");
        String wsDsl = wsRepo.getDslAtHead("main");
        assertEquals(SAMPLE_DSL, wsDsl,
                "Workspace repo should contain the shared DSL after sync");
    }

    @Test
    void roundTrip_publishAndSync() throws IOException {
        // Setup: system repo has initial content
        DslGitRepository sysRepo = factory.getSystemRepository();
        sysRepo.commitDsl("draft", "meta { language: \"taxdsl\"; }", "system", "init");

        // Alice creates content in workspace A
        DslGitRepository wsA = factory.getWorkspaceRepository("ws-A");
        wsA.commitDsl("main", SAMPLE_DSL, "alice", "alice work");

        // Alice publishes to shared
        syncService.publishFromWorkspaceToShared("alice", "ws-A");

        // Bob syncs from shared to his workspace
        syncService.syncFromSharedToWorkspace("bob", "ws-B");

        // Bob should see Alice's DSL
        DslGitRepository wsB = factory.getWorkspaceRepository("ws-B");
        assertEquals(SAMPLE_DSL, wsB.getDslAtHead("main"),
                "Bob's workspace should have Alice's published DSL");
    }

    @Test
    void publishFromWorkspaceToShared_failsWhenWorkspaceEmpty() {
        // Workspace has no content
        assertThrows(IOException.class,
                () -> syncService.publishFromWorkspaceToShared("alice", "ws-empty"),
                "Publish should fail when workspace has no content");
    }

    @Test
    void syncFromSharedToWorkspace_failsWhenSharedEmpty() {
        // System repo has no content on shared branch
        assertThrows(IOException.class,
                () -> syncService.syncFromSharedToWorkspace("bob", "ws-empty"),
                "Sync should fail when shared branch has no content");
    }

    @Test
    void syncFromSharedToWorkspace_updatesExistingContent() throws IOException {
        // System repo has initial content
        DslGitRepository sysRepo = factory.getSystemRepository();
        sysRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");

        // First sync
        syncService.syncFromSharedToWorkspace("bob", "ws-update");

        // Update system repo
        sysRepo.commitDsl("draft", UPDATED_DSL, "system", "update");

        // Second sync
        syncService.syncFromSharedToWorkspace("bob", "ws-update");

        // Workspace should have updated content
        DslGitRepository wsRepo = factory.getWorkspaceRepository("ws-update");
        assertEquals(UPDATED_DSL, wsRepo.getDslAtHead("main"),
                "Workspace should have updated DSL after re-sync");
    }

    @Test
    void crossRepoSync_withoutFactory_throwsException() {
        // Create service without factory (null)
        SyncIntegrationService legacyService = new SyncIntegrationService(
                syncStateRepo, new DslGitRepository(),
                mock(UserWorkspaceRepository.class),
                mock(SystemRepositoryService.class));

        assertThrows(IllegalStateException.class,
                () -> legacyService.syncFromSharedToWorkspace("alice", "ws-1"),
                "Should throw when factory is not configured");

        assertThrows(IllegalStateException.class,
                () -> legacyService.publishFromWorkspaceToShared("alice", "ws-1"),
                "Should throw when factory is not configured");
    }
}
