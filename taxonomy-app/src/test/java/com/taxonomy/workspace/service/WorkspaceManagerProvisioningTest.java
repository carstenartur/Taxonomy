package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.workspace.model.RepositoryTopologyMode;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for workspace provisioning in {@link WorkspaceManager}.
 *
 * <p>Verifies the lazy provisioning lifecycle:
 * {@code NOT_PROVISIONED → PROVISIONING → READY/FAILED}.
 */
class WorkspaceManagerProvisioningTest {

    private UserWorkspaceRepository wsRepo;
    private SystemRepositoryService sysRepoService;
    private DslGitRepository gitRepo;
    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        wsRepo = mock(UserWorkspaceRepository.class);
        sysRepoService = mock(SystemRepositoryService.class);
        gitRepo = new DslGitRepository(); // in-memory
        manager = new WorkspaceManager(wsRepo, 50, sysRepoService, gitRepo);

        // Default: save returns the argument
        when(wsRepo.save(any(UserWorkspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void provisionWorkspace_successfulProvisioning() throws IOException {
        // Set up a NOT_PROVISIONED workspace
        UserWorkspace ws = createWorkspace("alice", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(ws));

        // Set up a system repo with a draft branch that has content
        SystemRepository sysRepo = createSystemRepository();
        when(sysRepoService.getPrimaryRepository()).thenReturn(sysRepo);

        // Create initial commit on draft so the branch exists
        gitRepo.commitDsl("draft", "meta { language: \"taxdsl\"; }", "system", "initial");

        UserWorkspace result = manager.provisionWorkspaceRepository("alice");

        assertEquals(WorkspaceProvisioningStatus.READY, result.getProvisioningStatus());
        assertEquals("alice/workspace", result.getCurrentBranch());
        assertEquals("draft", result.getBaseBranch());
        assertEquals(sysRepo.getRepositoryId(), result.getSourceRepositoryId());
        assertEquals(RepositoryTopologyMode.INTERNAL_SHARED, result.getTopologyMode());
        assertNotNull(result.getProvisionedAt());
        assertNull(result.getProvisioningError());
    }

    @Test
    void provisionWorkspace_alreadyReady_isNoOp() {
        UserWorkspace ws = createWorkspace("alice", WorkspaceProvisioningStatus.READY);
        when(wsRepo.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(ws));

        UserWorkspace result = manager.provisionWorkspaceRepository("alice");

        assertEquals(WorkspaceProvisioningStatus.READY, result.getProvisioningStatus());
        // Should NOT call systemRepositoryService
        verify(sysRepoService, never()).getPrimaryRepository();
    }

    @Test
    void provisionWorkspace_failsGracefully() {
        UserWorkspace ws = createWorkspace("bob", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("bob")).thenReturn(Optional.of(ws));
        when(sysRepoService.getPrimaryRepository())
                .thenThrow(new IllegalStateException("No primary system repository configured"));

        assertThrows(RuntimeException.class,
                () -> manager.provisionWorkspaceRepository("bob"));

        assertEquals(WorkspaceProvisioningStatus.FAILED, ws.getProvisioningStatus());
        assertNotNull(ws.getProvisioningError());
    }

    @Test
    void provisionWorkspace_throwsForUnknownUser() {
        when(wsRepo.findByUsernameAndSharedFalse("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> manager.provisionWorkspaceRepository("unknown"));
    }

    @Test
    void findUserWorkspace_returnsExisting() {
        UserWorkspace ws = createWorkspace("alice", WorkspaceProvisioningStatus.READY);
        when(wsRepo.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(ws));

        UserWorkspace result = manager.findUserWorkspace("alice");
        assertNotNull(result);
        assertEquals("alice", result.getUsername());
    }

    @Test
    void findUserWorkspace_returnsNullForUnknown() {
        when(wsRepo.findByUsernameAndSharedFalse("unknown")).thenReturn(Optional.empty());

        assertNull(manager.findUserWorkspace("unknown"));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private UserWorkspace createWorkspace(String username, WorkspaceProvisioningStatus status) {
        UserWorkspace ws = new UserWorkspace();
        ws.setWorkspaceId(UUID.randomUUID().toString());
        ws.setUsername(username);
        ws.setDisplayName(username + "'s workspace");
        ws.setCurrentBranch("draft");
        ws.setBaseBranch("draft");
        ws.setShared(false);
        ws.setProvisioningStatus(status);
        ws.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        ws.setCreatedAt(Instant.now());
        ws.setLastAccessedAt(Instant.now());
        return ws;
    }

    private SystemRepository createSystemRepository() {
        SystemRepository sysRepo = new SystemRepository();
        sysRepo.setRepositoryId(UUID.randomUUID().toString());
        sysRepo.setDisplayName("Shared Architecture Repository");
        sysRepo.setTopologyMode(RepositoryTopologyMode.INTERNAL_SHARED);
        sysRepo.setDefaultBranch("draft");
        sysRepo.setPrimaryRepo(true);
        sysRepo.setCreatedAt(Instant.now());
        return sysRepo;
    }
}
