package com.taxonomy.versioning.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.NavigationReason;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ContextNavigationService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 * Tests verify per-user workspace isolation via the WorkspaceManager.
 */
class ContextNavigationServiceTest {

    private DslGitRepository gitRepo;
    private RepositoryStateService stateService;
    private ContextNavigationService navService;
    private WorkspaceManager workspaceManager;

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
        configureNavigation(50);
    }

    private void configureNavigation(int historyLimit) {
        var factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        workspaceManager = new WorkspaceManager(wsRepo, historyLimit,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class), gitRepo);
        stateService = new RepositoryStateService(factory, workspaceManager,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class));
        navService = new ContextNavigationService(factory, stateService, workspaceManager, historyLimit);
    }

    // ── getCurrentContext ────────────────────────────────────────────

    private static final String USER = "testuser";

    @Test
    void initialContextIsDraftEditable() {
        ContextRef ctx = navService.getCurrentContext(USER);

        assertNotNull(ctx);
        assertEquals("draft", ctx.branch());
        assertEquals(ContextMode.EDITABLE, ctx.mode());
        assertNull(ctx.originContextId());
        assertFalse(ctx.dirty());
    }

    @Test
    void initialContextHasUuid() {
        ContextRef ctx = navService.getCurrentContext(USER);
        assertNotNull(ctx.contextId());
        assertFalse(ctx.contextId().isBlank());
    }

    @Test
    void absentWorkspaceContextKeepsStableIdentityAcrossReads() {
        var emptyWorkspaces = mock(WorkspaceManager.class);
        var repositoryState = mock(RepositoryStateService.class);
        org.mockito.Mockito.when(repositoryState.resolveWorkspaceBranch("reader")).thenReturn("draft");
        var readOnlyNavigation = new ContextNavigationService(null, repositoryState, emptyWorkspaces, 50);

        ContextRef first = readOnlyNavigation.getCurrentContext("reader");
        ContextRef next = readOnlyNavigation.getCurrentContext("reader");

        assertEquals(first.contextId(), next.contextId());
        assertEquals("draft", next.branch());
        assertEquals(ContextMode.READ_ONLY, next.mode());
    }

    // ── openReadOnly ────────────────────────────────────────────────

    @Test
    void openReadOnlyCreatesReadOnlyContext() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        ContextRef ctx = navService.openReadOnly(USER, "draft", null, null, null);

        assertEquals(ContextMode.READ_ONLY, ctx.mode());
        assertEquals("draft", ctx.branch());
        assertNotNull(ctx.originContextId());
    }

    @Test
    void openReadOnlyWithSearchQuery() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        ContextRef ctx = navService.openReadOnly(USER, "draft", null, "CP-1023", "CP-1023");

        assertEquals(ContextMode.READ_ONLY, ctx.mode());
        assertEquals("CP-1023", ctx.openedFromSearch());
        assertEquals("CP-1023", ctx.matchedElementId());
    }

    @Test
    void openReadOnlyWithSpecificCommit() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        ContextRef ctx = navService.openReadOnly(USER, "draft", commitId, null, null);

        assertEquals(commitId, ctx.commitId());
    }

    @Test
    void openReadOnlyRecordsNavigation() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, "test query", null);

        List<ContextHistoryEntry> history = navService.getHistory(USER);
        assertEquals(1, history.size());
        assertEquals(NavigationReason.SEARCH_OPEN, history.get(0).reason());
    }

    @Test
    void openReadOnlyWithoutSearchRecordsManualSwitch() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, null, null);

        List<ContextHistoryEntry> history = navService.getHistory(USER);
        assertEquals(1, history.size());
        assertEquals(NavigationReason.MANUAL_SWITCH, history.get(0).reason());
    }

    // ── switchContext ───────────────────────────────────────────────

    @Test
    void switchContextCreatesEditableContext() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        gitRepo.createBranch("feature", "draft");

        ContextRef ctx = navService.switchContext(USER, "feature", null);

        assertEquals(ContextMode.EDITABLE, ctx.mode());
        assertEquals("feature", ctx.branch());
    }

    @Test
    void switchContextRecordsNavigation() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        gitRepo.createBranch("feature", "draft");

        navService.switchContext(USER, "feature", null);

        List<ContextHistoryEntry> history = navService.getHistory(USER);
        assertEquals(1, history.size());
        assertEquals(NavigationReason.MANUAL_SWITCH, history.get(0).reason());
    }

    // ── returnToOrigin ──────────────────────────────────────────────

    @Test
    void returnToOriginRestoresOriginalBranch() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, null, null);
        ContextRef returned = navService.returnToOrigin(USER);

        assertEquals("draft", returned.branch());
        assertEquals(ContextMode.EDITABLE, returned.mode());
    }

    @Test
    void returnToOriginWithNoOriginReturnsCurrent() {
        ContextRef ctx = navService.getCurrentContext(USER);
        ContextRef returned = navService.returnToOrigin(USER);

        // Should return the same context since there is no origin
        assertEquals(ctx.contextId(), returned.contextId());
    }

    @Test
    void returnToOriginRecordsReturnReason() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        navService.openReadOnly(USER, "draft", null, null, null);

        navService.returnToOrigin(USER);

        List<ContextHistoryEntry> history = navService.getHistory(USER);
        assertEquals(2, history.size());
        assertEquals(NavigationReason.RETURN, history.get(1).reason());
    }

    // ── back ────────────────────────────────────────────────────────

    @Test
    void backNavigatesToPreviousContext() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, null, null);
        ContextRef back = navService.back(USER);

        assertEquals("draft", back.branch());
        assertEquals(ContextMode.EDITABLE, back.mode());
    }

    @Test
    void backOnEmptyHistoryReturnsCurrent() {
        ContextRef current = navService.getCurrentContext(USER);
        ContextRef back = navService.back(USER);

        assertEquals(current.contextId(), back.contextId());
    }

    @Test
    void backRemovesLastHistoryEntry() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, null, null);
        assertEquals(1, navService.getHistory(USER).size());

        navService.back(USER);
        assertEquals(0, navService.getHistory(USER).size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void navigationRestoresThePriorReadOnlyStandAndKeepsItsOrigin(boolean returnToOrigin) throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        ContextRef draft = navService.getCurrentContext(USER);
        ContextRef first = navService.openReadOnly(USER, "draft", null, "original search", "original match");
        ContextRef second = navService.openReadOnly(USER, "draft", first.commitId(), null, null);

        ContextRef restored = returnToOrigin ? navService.returnToOrigin(USER) : navService.back(USER);
        assertAll(
                () -> assertNotEquals(first.contextId(), restored.contextId()),
                () -> assertNotEquals(second.contextId(), restored.contextId()),
                () -> assertEquals(first.branch(), restored.branch()),
                () -> assertEquals(first.commitId(), restored.commitId()),
                () -> assertEquals(ContextMode.READ_ONLY, restored.mode()),
                () -> assertEquals(draft.contextId(), restored.originContextId()),
                () -> assertEquals(first.openedFromSearch(), restored.openedFromSearch()),
                () -> assertEquals(first.matchedElementId(), restored.matchedElementId()));
        assertEquals(ContextMode.EDITABLE, navService.back(USER).mode());
    }

    @Test
    void simultaneousCommandsForOneContextIdentityCanOnlyRestoreOnce() throws Exception {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        ContextRef snapshot = navService.openReadOnly(USER, "draft", null, null, null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var back = executor.submit(() -> navigationStatus(start, snapshot.contextId(), false));
            var origin = executor.submit(() -> navigationStatus(start, snapshot.contextId(), true));
            start.countDown();
            assertEquals(List.of(200, 409), List.of(back.get(5, TimeUnit.SECONDS), origin.get(5, TimeUnit.SECONDS))
                    .stream().sorted().toList());
        }
        assertEquals(ContextMode.EDITABLE, navService.getCurrentContext(USER).mode());
    }

    @Test
    void disabledHistoryStillAllowsReturnToTheImmediateOrigin() throws IOException {
        configureNavigation(0);
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        ContextRef snapshot = navService.openReadOnly(USER, "draft", null, null, null);
        assertTrue(navService.getHistory(USER).isEmpty());
        assertEquals(snapshot, navService.back(USER));
        assertEquals(ContextMode.EDITABLE, navService.returnToOrigin(USER).mode());
        assertTrue(navService.getHistory(USER).isEmpty());
    }

    private int navigationStatus(CountDownLatch start, String currentContext, boolean returnToOrigin)
            throws InterruptedException {
        start.await();
        try {
            if (returnToOrigin) navService.returnToOrigin(USER, WorkspaceContext.SHARED, currentContext);
            else navService.back(USER, WorkspaceContext.SHARED, currentContext);
            return 200;
        } catch (ResponseStatusException error) {
            return error.getStatusCode().value();
        }
    }

    // ── getHistory ──────────────────────────────────────────────────

    @Test
    void emptyHistoryByDefault() {
        assertTrue(navService.getHistory(USER).isEmpty());
    }

    @Test
    void historyGrowsWithNavigations() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        gitRepo.createBranch("feature", "draft");

        navService.openReadOnly(USER, "draft", null, null, null);
        navService.switchContext(USER, "feature", null);
        navService.openReadOnly(USER, "draft", null, "query", null);

        assertEquals(3, navService.getHistory(USER).size());
    }

    @Test
    void historyEntriesHaveTimestamps() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, null, null);

        ContextHistoryEntry entry = navService.getHistory(USER).get(0);
        assertNotNull(entry.createdAt());
        assertNotNull(entry.fromContextId());
        assertNotNull(entry.toContextId());
    }

    // ── createVariantFromCurrent ────────────────────────────────────

    @Test
    void createVariantCreatesNewBranch() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        ContextRef variant = navService.createVariantFromCurrent(USER, "my-variant");

        assertEquals("my-variant", variant.branch());
        assertEquals(ContextMode.EDITABLE, variant.mode());
        assertTrue(gitRepo.getBranchNames().contains("my-variant"));
    }

    @Test
    void createVariantRecordsNavigation() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.createVariantFromCurrent(USER, "my-variant");

        List<ContextHistoryEntry> history = navService.getHistory(USER);
        assertEquals(1, history.size());
        assertEquals(NavigationReason.VARIANT_CREATED, history.get(0).reason());
    }

    @Test
    void createVariantPreservesOrigin() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        String initialId = navService.getCurrentContext(USER).contextId();

        ContextRef variant = navService.createVariantFromCurrent(USER, "my-variant");

        assertEquals(initialId, variant.originContextId());
        assertEquals("draft", variant.originBranch());
    }

    // ── isReadOnly ──────────────────────────────────────────────────

    @Test
    void initialContextIsNotReadOnly() {
        assertFalse(navService.isReadOnly(USER));
    }

    @Test
    void readOnlyContextIsReadOnly() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, null, null);

        assertTrue(navService.isReadOnly(USER));
    }

    @Test
    void switchedContextIsNotReadOnly() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        // First go to read-only
        navService.openReadOnly(USER, "draft", null, null, null);
        assertTrue(navService.isReadOnly(USER));

        // Then switch to editable
        navService.switchContext(USER, "draft", null);
        assertFalse(navService.isReadOnly(USER));
    }

    @Test
    void returnToOriginResetsReadOnly() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        navService.openReadOnly(USER, "draft", null, null, null);
        assertTrue(navService.isReadOnly(USER));

        navService.returnToOrigin(USER);
        assertFalse(navService.isReadOnly(USER));
    }
}
