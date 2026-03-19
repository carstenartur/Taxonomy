package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dto.ContextHistoryEntry;
import com.taxonomy.dto.ContextMode;
import com.taxonomy.dto.ContextRef;
import com.taxonomy.dto.NavigationReason;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.taxonomy.workspace.service.WorkspaceManager;

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
        gitRepo = new DslGitRepository();
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        workspaceManager = new WorkspaceManager(wsRepo, 50,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class), gitRepo);
        stateService = new RepositoryStateService(gitRepo, workspaceManager);
        navService = new ContextNavigationService(gitRepo, stateService, workspaceManager, 50);
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
