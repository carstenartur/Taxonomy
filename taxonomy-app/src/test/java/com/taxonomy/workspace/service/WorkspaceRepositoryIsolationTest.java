package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
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
 * Tests for workspace provisioning with per-workspace repository isolation.
 *
 * <p>Verifies that when a {@link DslGitRepositoryFactory} is provided,
 * provisioning creates a separate repository per workspace instead of
 * a branch in the shared repository.
 */
class WorkspaceRepositoryIsolationTest {

    private UserWorkspaceRepository wsRepo;
    private SystemRepositoryService sysRepoService;
    private DslGitRepositoryFactory factory;
    private WorkspaceManager manager;

    @BeforeEach
    void setUp() {
        wsRepo = mock(UserWorkspaceRepository.class);
        sysRepoService = mock(SystemRepositoryService.class);
        factory = new DslGitRepositoryFactory(null); // in-memory mode

        DslGitRepository sysGitRepo = factory.getSystemRepository();
        manager = new WorkspaceManager(wsRepo, 50, sysRepoService, sysGitRepo, factory);

        when(wsRepo.save(any(UserWorkspace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void provisionWorkspace_createsOwnRepository() throws IOException {
        UserWorkspace ws = createWorkspace("alice", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(ws));

        SystemRepository sysRepo = createSystemRepository();
        when(sysRepoService.getPrimaryRepository()).thenReturn(sysRepo);

        // Add initial content to system repo
        factory.getSystemRepository().commitDsl("draft",
                "meta { language: \"taxdsl\"; }", "system", "initial");

        UserWorkspace result = manager.provisionWorkspaceRepository("alice");

        assertEquals(WorkspaceProvisioningStatus.READY, result.getProvisioningStatus());
        assertEquals("main", result.getCurrentBranch(),
                "Factory-provisioned workspace should use 'main' branch");
        assertNotNull(result.getCurrentCommit());
    }

    @Test
    void provisionWorkspace_forksDslFromSystemRepo() throws IOException {
        String dsl = "meta { language: \"taxdsl\"; version: \"2.0\"; }";

        // System repo has content
        factory.getSystemRepository().commitDsl("draft", dsl, "system", "initial");

        UserWorkspace ws = createWorkspace("bob", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("bob")).thenReturn(Optional.of(ws));
        when(sysRepoService.getPrimaryRepository()).thenReturn(createSystemRepository());

        manager.provisionWorkspaceRepository("bob");

        // Workspace repo should have the same DSL
        DslGitRepository wsGitRepo = factory.getWorkspaceRepository(ws.getWorkspaceId());
        String wsDsl = wsGitRepo.getDslAtHead("main");
        assertEquals(dsl, wsDsl, "Workspace should have forked DSL from system repo");
    }

    @Test
    void twoWorkspaces_areIsolated() throws IOException {
        // System repo has initial content
        factory.getSystemRepository().commitDsl("draft",
                "meta { language: \"taxdsl\"; }", "system", "initial");

        // Provision workspace A
        UserWorkspace wsA = createWorkspace("alice", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(wsA));
        when(sysRepoService.getPrimaryRepository()).thenReturn(createSystemRepository());
        manager.provisionWorkspaceRepository("alice");

        // Commit something unique to workspace A
        String dslA = "meta { language: \"taxdsl\"; } element CP-1023 type Capability { title: \"Alpha\"; }";
        factory.getWorkspaceRepository(wsA.getWorkspaceId())
                .commitDsl("main", dslA, "alice", "alpha change");

        // Provision workspace B
        UserWorkspace wsB = createWorkspace("bob", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("bob")).thenReturn(Optional.of(wsB));
        manager.provisionWorkspaceRepository("bob");

        // Workspace B should NOT see workspace A's commit
        DslGitRepository repoBGit = factory.getWorkspaceRepository(wsB.getWorkspaceId());
        String bDsl = repoBGit.getDslAtHead("main");
        assertFalse(bDsl.contains("Alpha"),
                "Workspace B should not see workspace A's unique content");
    }

    @Test
    void systemRepo_unaffectedByWorkspaceCommits() throws IOException {
        // System repo has initial content
        String initialDsl = "meta { language: \"taxdsl\"; }";
        factory.getSystemRepository().commitDsl("draft", initialDsl, "system", "initial");

        // Provision and modify workspace
        UserWorkspace ws = createWorkspace("carol", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("carol")).thenReturn(Optional.of(ws));
        when(sysRepoService.getPrimaryRepository()).thenReturn(createSystemRepository());
        manager.provisionWorkspaceRepository("carol");

        String modifiedDsl = "meta { language: \"taxdsl\"; } element CP-1023 type Capability { title: \"Modified\"; }";
        factory.getWorkspaceRepository(ws.getWorkspaceId())
                .commitDsl("main", modifiedDsl, "carol", "workspace edit");

        // System repo should still have original content
        String sysDsl = factory.getSystemRepository().getDslAtHead("draft");
        assertEquals(initialDsl, sysDsl,
                "System repo should be unaffected by workspace commits");
    }

    @Test
    void backwardCompatibility_legacyConstructorStillWorks() throws IOException {
        // Create manager without factory (legacy mode)
        DslGitRepository gitRepo = new DslGitRepository();
        WorkspaceManager legacyManager = new WorkspaceManager(wsRepo, 50, sysRepoService, gitRepo);

        // System repo with content
        SystemRepository sysRepo = createSystemRepository();
        when(sysRepoService.getPrimaryRepository()).thenReturn(sysRepo);
        gitRepo.commitDsl("draft", "meta { language: \"taxdsl\"; }", "system", "initial");

        UserWorkspace ws = createWorkspace("alice", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        when(wsRepo.findByUsernameAndSharedFalse("alice")).thenReturn(Optional.of(ws));

        UserWorkspace result = legacyManager.provisionWorkspaceRepository("alice");

        assertEquals(WorkspaceProvisioningStatus.READY, result.getProvisioningStatus());
        assertTrue(result.getCurrentBranch().startsWith("alice/workspace/"),
                "Legacy mode should use branch-based isolation");
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
