package com.taxonomy.workspace.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.NavigationReason;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.dto.WorkspaceInfo;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.taxonomy.versioning.service.ContextNavigationService;
import com.taxonomy.versioning.service.RepositoryStateService;

/**
 * Tests for multi-user workspace isolation.
 *
 * <p>Verifies that different users have independent context navigation,
 * projection tracking, and operation state. No Spring context required.
 */
class MultiUserWorkspaceIsolationTest {

    private DslGitRepository gitRepo;
    private WorkspaceManager workspaceManager;
    private ContextNavigationService navService;
    private RepositoryStateService stateService;
    private RepositoryStateGuard guard;

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
        var factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        workspaceManager = new WorkspaceManager(wsRepo, 50,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class), gitRepo);
        stateService = new RepositoryStateService(factory, workspaceManager,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class));
        navService = new ContextNavigationService(factory, stateService, workspaceManager, 50);
        guard = new RepositoryStateGuard(stateService, navService);
    }

    // ── Per-user context isolation ──────────────────────────────────

    @Test
    void differentUsersHaveIndependentContexts() {
        ContextRef aliceCtx = navService.getCurrentContext("alice");
        ContextRef bobCtx = navService.getCurrentContext("bob");

        assertNotNull(aliceCtx);
        assertNotNull(bobCtx);
        assertNotEquals(aliceCtx.contextId(), bobCtx.contextId(),
                "Users should have different context IDs");
        assertEquals("draft", aliceCtx.branch());
        assertEquals("draft", bobCtx.branch());
    }

    @Test
    void userNavigationDoesNotAffectOtherUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("feature", "draft");

        // Alice switches to feature branch
        navService.switchContext("alice", "feature", null);

        // Bob's context should still be on draft
        ContextRef aliceCtx = navService.getCurrentContext("alice");
        ContextRef bobCtx = navService.getCurrentContext("bob");

        assertEquals("feature", aliceCtx.branch());
        assertEquals("draft", bobCtx.branch());
    }

    @Test
    void readOnlyStateIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Alice opens read-only context
        navService.openReadOnly("alice", "draft", null, null, null);

        // Alice is read-only, Bob is not
        assertTrue(navService.isReadOnly("alice"));
        assertFalse(navService.isReadOnly("bob"));
    }

    @Test
    void navigationHistoryIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("feature", "draft");

        // Alice does two navigations
        navService.openReadOnly("alice", "draft", null, null, null);
        navService.switchContext("alice", "feature", null);

        // Bob does one navigation
        navService.openReadOnly("bob", "draft", null, "query", null);

        // History should be independent
        List<ContextHistoryEntry> aliceHistory = navService.getHistory("alice");
        List<ContextHistoryEntry> bobHistory = navService.getHistory("bob");

        assertEquals(2, aliceHistory.size());
        assertEquals(1, bobHistory.size());

        // Bob's navigation was a search, Alice's was manual
        assertEquals(NavigationReason.SEARCH_OPEN, bobHistory.get(0).reason());
        assertEquals(NavigationReason.MANUAL_SWITCH, aliceHistory.get(0).reason());
    }

    @Test
    void backNavigationIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Both users navigate
        navService.openReadOnly("alice", "draft", null, null, null);
        navService.openReadOnly("bob", "draft", null, null, null);

        // Alice goes back
        navService.back("alice");

        // Alice's history is empty, Bob's still has one entry
        assertEquals(0, navService.getHistory("alice").size());
        assertEquals(1, navService.getHistory("bob").size());
    }

    @Test
    void returnToOriginIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");
        gitRepo.createBranch("feature", "draft");

        // Alice opens read-only on feature
        navService.openReadOnly("alice", "feature", null, null, null);
        // Bob switches to feature
        navService.switchContext("bob", "feature", null);

        // Alice returns to origin
        ContextRef aliceReturned = navService.returnToOrigin("alice");
        assertEquals("draft", aliceReturned.branch());
        assertEquals(ContextMode.EDITABLE, aliceReturned.mode());

        // Bob is still on feature
        ContextRef bobCtx = navService.getCurrentContext("bob");
        assertEquals("feature", bobCtx.branch());
    }

    @Test
    void createVariantIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Alice creates a variant
        ContextRef variant = navService.createVariantFromCurrent("alice", "alice-variant");
        assertEquals("alice-variant", variant.branch());

        // Alice is on the variant branch
        assertEquals("alice-variant", navService.getCurrentContext("alice").branch());

        // Bob is still on draft
        assertEquals("draft", navService.getCurrentContext("bob").branch());
    }

    // ── Per-user projection tracking ────────────────────────────────

    @Test
    void projectionTrackingIsPerUser() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Alice records a projection
        stateService.recordProjection("alice", commitId, "draft");

        // Alice's state shows projection, Bob's does not
        RepositoryState aliceState = stateService.getState("alice", "draft");
        RepositoryState bobState = stateService.getState("bob", "draft");

        assertEquals(commitId, aliceState.projectionCommit());
        assertNull(bobState.projectionCommit());

        assertFalse(aliceState.projectionStale());
        assertFalse(bobState.projectionStale()); // null → not stale
    }

    @Test
    void projectionStalenessIsPerUser() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");

        // Both users record projection at same commit
        stateService.recordProjection("alice", first, "draft");
        stateService.recordProjection("bob", first, "draft");

        // New commit moves HEAD ahead
        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");

        // Both are stale
        assertTrue(stateService.isProjectionStale("alice", "draft"));
        assertTrue(stateService.isProjectionStale("bob", "draft"));

        // Alice re-materializes
        String second = gitRepo.getHeadCommit("draft");
        stateService.recordProjection("alice", second, "draft");

        // Alice is fresh, Bob is still stale
        assertFalse(stateService.isProjectionStale("alice", "draft"));
        assertTrue(stateService.isProjectionStale("bob", "draft"));
    }

    @Test
    void indexTrackingIsPerUser() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        stateService.recordIndexBuild("alice", commitId);

        RepositoryState aliceState = stateService.getState("alice", "draft");
        RepositoryState bobState = stateService.getState("bob", "draft");

        assertEquals(commitId, aliceState.indexCommit());
        assertNull(bobState.indexCommit());
    }

    // ── Per-user operation tracking ─────────────────────────────────

    @Test
    void operationTrackingIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Alice starts a merge operation
        stateService.beginOperation("alice", "merge");

        RepositoryState aliceState = stateService.getState("alice", "draft");
        RepositoryState bobState = stateService.getState("bob", "draft");

        assertTrue(aliceState.operationInProgress());
        assertEquals("merge", aliceState.operationKind());

        assertFalse(bobState.operationInProgress());
        assertNull(bobState.operationKind());
    }

    @Test
    void operationBlockingIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Alice starts a merge
        stateService.beginOperation("alice", "merge");

        // Alice's operations should be blocked
        var aliceCheck = guard.checkWriteOperation("alice", "draft", "commit");
        assertFalse(aliceCheck.allowed());
        assertTrue(aliceCheck.blocks().stream().anyMatch(b -> b.contains("merge")));

        // Bob's operations should NOT be blocked
        var bobCheck = guard.checkWriteOperation("bob", "draft", "commit");
        assertTrue(bobCheck.allowed());
    }

    @Test
    void readOnlyGuardIsPerUser() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "tester", "initial");

        // Alice opens read-only
        navService.openReadOnly("alice", "draft", null, null, null);

        // Alice should be blocked from writing
        var aliceCheck = guard.checkWriteOperation("alice", "draft", "commit");
        assertFalse(aliceCheck.allowed());
        assertTrue(aliceCheck.blocks().stream().anyMatch(b -> b.contains("read-only")));

        // Bob should be allowed to write
        var bobCheck = guard.checkWriteOperation("bob", "draft", "commit");
        assertTrue(bobCheck.allowed());
    }

    // ── Workspace Manager ───────────────────────────────────────────

    @Test
    void workspaceManagerCreatesWorkspacesOnDemand() {
        assertEquals(0, workspaceManager.getActiveWorkspaceCount());

        workspaceManager.getOrCreateWorkspace("alice");
        assertEquals(1, workspaceManager.getActiveWorkspaceCount());

        workspaceManager.getOrCreateWorkspace("bob");
        assertEquals(2, workspaceManager.getActiveWorkspaceCount());

        // Same user doesn't create new workspace
        workspaceManager.getOrCreateWorkspace("alice");
        assertEquals(2, workspaceManager.getActiveWorkspaceCount());
    }

    @Test
    void workspaceManagerReturnsSameStateForSameUser() {
        UserWorkspaceState first = workspaceManager.getOrCreateWorkspace("alice");
        UserWorkspaceState second = workspaceManager.getOrCreateWorkspace("alice");
        assertSame(first, second);
    }

    @Test
    void workspaceEvictionRemovesState() {
        workspaceManager.getOrCreateWorkspace("alice");
        assertEquals(1, workspaceManager.getActiveWorkspaceCount());

        workspaceManager.evictWorkspace("alice");
        assertEquals(0, workspaceManager.getActiveWorkspaceCount());
        assertNull(workspaceManager.getWorkspace("alice"));
    }

    @Test
    void listActiveWorkspacesReturnsAll() {
        workspaceManager.getOrCreateWorkspace("alice");
        workspaceManager.getOrCreateWorkspace("bob");

        List<WorkspaceInfo> workspaces = workspaceManager.listActiveWorkspaces();
        assertEquals(2, workspaces.size());
    }

    @Test
    void workspaceInfoContainsUserDetails() {
        WorkspaceInfo info = workspaceManager.getWorkspaceInfo("alice");

        assertEquals("alice", info.username());
        assertNotNull(info.currentContext());
        assertEquals("draft", info.currentBranch());
    }

    @Test
    void nullOrBlankUsernameDefaultsToAnonymous() {
        UserWorkspaceState nullState = workspaceManager.getOrCreateWorkspace(null);
        UserWorkspaceState blankState = workspaceManager.getOrCreateWorkspace("");

        assertSame(nullState, blankState);
        assertEquals(WorkspaceManager.DEFAULT_USER, nullState.getUsername());
    }

    // ── Concurrent user scenario ────────────────────────────────────

    @Test
    void threeUsersCanWorkIndependently() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "system", "initial");
        gitRepo.createBranch("feature-a", "draft");
        gitRepo.createBranch("feature-b", "draft");

        // Each user switches to a different branch
        navService.switchContext("alice", "feature-a", null);
        navService.switchContext("bob", "feature-b", null);
        // Carol stays on draft

        assertEquals("feature-a", navService.getCurrentContext("alice").branch());
        assertEquals("feature-b", navService.getCurrentContext("bob").branch());
        assertEquals("draft", navService.getCurrentContext("carol").branch());

        // Each user's history is independent
        assertEquals(1, navService.getHistory("alice").size());
        assertEquals(1, navService.getHistory("bob").size());
        assertEquals(0, navService.getHistory("carol").size());

        // Operations are independent
        stateService.beginOperation("alice", "merge");
        assertTrue(stateService.getState("alice", "feature-a").operationInProgress());
        assertFalse(stateService.getState("bob", "feature-b").operationInProgress());
        assertFalse(stateService.getState("carol", "draft").operationInProgress());
    }
}
