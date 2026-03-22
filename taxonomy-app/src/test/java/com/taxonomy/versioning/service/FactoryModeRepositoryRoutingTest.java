package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests that services correctly route to per-workspace repositories
 * in factory mode, rather than always using the system repository.
 *
 * <p>Each test creates both a system and a workspace repository via
 * the factory, commits data to the workspace repository, and verifies
 * that the service resolves the correct repository when given an
 * explicit {@link WorkspaceContext} parameter.
 *
 * <p>Services accept {@code WorkspaceContext} as an explicit parameter
 * rather than relying on {@code resolveCurrentContext()}, which is
 * reserved for the Facade/UI layer. This makes services testable
 * without a SecurityContext.
 */
class FactoryModeRepositoryRoutingTest {

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

    private DslGitRepositoryFactory factory;
    private DslGitRepository systemRepo;
    private DslGitRepository workspaceRepo;
    private WorkspaceContext wsCtx;

    @BeforeEach
    void setUp() {
        factory = new DslGitRepositoryFactory(null); // in-memory mode
        systemRepo = factory.getSystemRepository();
        workspaceRepo = factory.getWorkspaceRepository("alice-ws");
        wsCtx = new WorkspaceContext("alice", "alice-ws", "main");
    }

    // ── RepositoryStateService ──────────────────────────────────────

    @Test
    void repositoryStateService_seesWorkspaceCommits() throws IOException {
        // Commit only to workspace repo
        String commitId = workspaceRepo.commitDsl("main", SAMPLE_DSL, "alice", "workspace commit");

        // Service with explicit workspace context should see the commit
        RepositoryStateService wsStateService = createStateService(workspaceRepo);
        RepositoryState state = wsStateService.getState("alice", "main", wsCtx);

        assertEquals(commitId, state.headCommit(),
                "Service with workspace context should see workspace commit");
        assertEquals(1, state.totalCommits());
    }

    @Test
    void repositoryStateService_sharedContextSeesOnlySystemRepo() throws IOException {
        // Commit only to workspace repo
        workspaceRepo.commitDsl("main", SAMPLE_DSL, "alice", "workspace commit");

        // Service with SHARED context should NOT see the workspace commit
        RepositoryStateService sharedStateService = createStateService(systemRepo);
        RepositoryState state = sharedStateService.getState("alice", "main", WorkspaceContext.SHARED);

        assertNull(state.headCommit(),
                "Service with shared context should not see workspace commit");
        assertEquals(0, state.totalCommits());
    }

    @Test
    void repositoryStateService_stalenessDetectsWorkspaceChanges() throws IOException {
        // Commit to workspace repo and record projection
        String first = workspaceRepo.commitDsl("main", SAMPLE_DSL, "alice", "first");

        RepositoryStateService wsStateService = createStateService(workspaceRepo);
        wsStateService.recordProjection("alice", first, "main");

        assertFalse(wsStateService.isProjectionStale("alice", "main", wsCtx),
                "Projection at HEAD should not be stale");

        // New commit to workspace repo should make projection stale
        workspaceRepo.commitDsl("main", SAMPLE_DSL, "alice", "second");

        assertTrue(wsStateService.isProjectionStale("alice", "main", wsCtx),
                "Projection should be stale after new workspace commit");
    }

    // ── ContextNavigationService ────────────────────────────────────

    @Test
    void contextNavigationService_resolvesCommitFromWorkspaceRepo() throws IOException {
        // Commit only to workspace repo
        String commitId = workspaceRepo.commitDsl("main", SAMPLE_DSL, "alice", "workspace commit");

        ContextNavigationService navService = createNavService(workspaceRepo);
        var ctx = navService.switchContext("alice", "main", null, wsCtx);

        assertEquals(commitId, ctx.commitId(),
                "Navigation service should resolve HEAD from workspace repo");
    }

    @Test
    void contextNavigationService_createVariantInWorkspaceRepo() throws IOException {
        // Commit to workspace repo on "draft" (the default context branch)
        workspaceRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        ContextNavigationService navService = createNavService(workspaceRepo);
        var variant = navService.createVariantFromCurrent("alice", "my-variant", wsCtx);

        assertEquals("my-variant", variant.branch());
        assertTrue(workspaceRepo.getBranchNames().contains("my-variant"),
                "Variant branch should be created in workspace repo");
        assertFalse(systemRepo.getBranchNames().contains("my-variant"),
                "Variant branch should NOT appear in system repo");
    }

    // ── ConflictDetectionService ────────────────────────────────────

    @Test
    void conflictDetectionService_seesWorkspaceBranches() throws IOException {
        // Set up branches in workspace repo
        workspaceRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        workspaceRepo.createBranch("review", "draft");

        // Service with workspace context should see both branches
        ConflictDetectionService wsConflictService = new ConflictDetectionService(factory);
        var preview = wsConflictService.previewMerge("draft", "review", wsCtx);

        assertTrue(preview.canMerge(),
                "Service with workspace context should find both branches");
        assertTrue(preview.alreadyMerged(),
                "Branches should be detected as already merged (same commit)");
    }

    @Test
    void conflictDetectionService_workspaceBranchesInvisibleWithSharedContext() throws IOException {
        // Set up branches in workspace repo only
        workspaceRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        workspaceRepo.createBranch("review", "draft");

        // Service with shared context should NOT see workspace branches
        ConflictDetectionService sharedConflictService = new ConflictDetectionService(factory);
        var preview = sharedConflictService.previewMerge("draft", "review", WorkspaceContext.SHARED);

        assertFalse(preview.canMerge(),
                "Service with shared context should not find workspace branches");
        assertTrue(preview.warnings().stream().anyMatch(w -> w.contains("not found")),
                "Should report branch not found");
    }

    // ── SHARED/system-repo sanity ───────────────────────────────────

    @Test
    void sharedContextStillUsesSystemRepository() throws IOException {
        // Commit to SYSTEM repo
        String systemCommitId = systemRepo.commitDsl("draft", SAMPLE_DSL, "system", "system commit");

        // Commit to WORKSPACE repo (should be invisible in SHARED context)
        workspaceRepo.commitDsl("draft", SAMPLE_DSL, "alice", "workspace commit");

        // SHARED context should see system commits, NOT workspace commits
        RepositoryStateService stateService = createStateService(systemRepo);
        RepositoryState state = stateService.getState("system", "draft", WorkspaceContext.SHARED);

        assertEquals(systemCommitId, state.headCommit(),
                "SHARED context should see system repo commits");
        assertEquals(1, state.totalCommits(),
                "SHARED context should see exactly 1 commit from system repo");

        // Conflict detection in SHARED context should see system branches
        ConflictDetectionService conflictService = new ConflictDetectionService(factory);
        var preview = conflictService.previewMerge("draft", "draft", WorkspaceContext.SHARED);
        assertTrue(preview.canMerge(),
                "SHARED context should find system repo branches");
    }

    // ── Cross-workspace isolation ───────────────────────────────────

    @Test
    void crossWorkspaceIsolation_separateRepos() throws IOException {
        // Create two workspace repos
        DslGitRepository wsA = factory.getWorkspaceRepository("ws-A");
        DslGitRepository wsB = factory.getWorkspaceRepository("ws-B");

        // Commit to each
        String commitA = wsA.commitDsl("main", SAMPLE_DSL, "alice", "Alice's commit");
        String commitB = wsB.commitDsl("main", SAMPLE_DSL, "bob", "Bob's commit");

        // Verify isolation: A should not see B's commits and vice versa
        assertNotEquals(commitA, commitB, "Different repos should have different commit SHAs");

        // Service routing for workspace A
        WorkspaceContext ctxA = new WorkspaceContext("alice", "ws-A", "main");
        RepositoryStateService stateA = createStateService(wsA);
        assertEquals(commitA, stateA.getState("alice", "main", ctxA).headCommit(),
                "Service for workspace A should see A's commit");

        // Service routing for workspace B
        WorkspaceContext ctxB = new WorkspaceContext("bob", "ws-B", "main");
        RepositoryStateService stateB = createStateService(wsB);
        assertEquals(commitB, stateB.getState("bob", "main", ctxB).headCommit(),
                "Service for workspace B should see B's commit");
    }

    // ── Helper methods ──────────────────────────────────────────────

    private RepositoryStateService createStateService(DslGitRepository repo) {
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        WorkspaceManager wm = new WorkspaceManager(wsRepo, 50,
                mock(SystemRepositoryService.class), repo);
        return new RepositoryStateService(factory, wm,
                mock(SystemRepositoryService.class));
    }

    private ContextNavigationService createNavService(DslGitRepository repo) {
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        WorkspaceManager wm = new WorkspaceManager(wsRepo, 50,
                mock(SystemRepositoryService.class), repo);
        RepositoryStateService stateService = new RepositoryStateService(factory, wm,
                mock(SystemRepositoryService.class));
        return new ContextNavigationService(factory, stateService, wm, 50);
    }
}
