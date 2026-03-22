package com.taxonomy.versioning.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ProjectionState;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.taxonomy.workspace.service.WorkspaceManager;

/**
 * Unit tests for {@link RepositoryStateService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 * Tests verify per-user workspace isolation via the WorkspaceManager.
 */
class RepositoryStateServiceTest {

    private DslGitRepository gitRepo;
    private RepositoryStateService stateService;
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
        var factory = new DslGitRepositoryFactory(null);
        gitRepo = factory.getSystemRepository();
        UserWorkspaceRepository wsRepo = mock(UserWorkspaceRepository.class);
        workspaceManager = new WorkspaceManager(wsRepo, 50,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class), gitRepo);
        stateService = new RepositoryStateService(factory, workspaceManager,
                mock(com.taxonomy.workspace.service.SystemRepositoryService.class));
    }

    // ── getState ────────────────────────────────────────────────────

    private static final String USER = "testuser";

    @Test
    void getStateOnEmptyBranchReturnsDefaults() {
        RepositoryState state = stateService.getState(USER, "draft");

        assertEquals("draft", state.currentBranch());
        assertNull(state.headCommit());
        assertNull(state.headTimestamp());
        assertNull(state.headAuthor());
        assertNull(state.headMessage());
        assertTrue(state.branches().isEmpty());
        assertFalse(state.operationInProgress());
        assertNull(state.operationKind());
        assertEquals(0, state.totalCommits());
        assertFalse(state.databaseBacked());
    }

    @Test
    void getStateReturnsHeadInfo() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");

        RepositoryState state = stateService.getState(USER, "draft");

        assertEquals("draft", state.currentBranch());
        assertEquals(commitId, state.headCommit());
        assertEquals("alice", state.headAuthor());
        assertEquals("initial", state.headMessage());
        assertNotNull(state.headTimestamp());
        assertEquals(1, state.totalCommits());
        assertTrue(state.branches().contains("draft"));
    }

    @Test
    void getStateShowsMultipleBranches() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        gitRepo.createBranch("review", "draft");

        RepositoryState state = stateService.getState(USER, "draft");

        assertEquals(2, state.branches().size());
        assertTrue(state.branches().contains("draft"));
        assertTrue(state.branches().contains("review"));
    }

    // ── Projection tracking ─────────────────────────────────────────

    @Test
    void projectionNotStaleWhenNoProjectionRecorded() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        RepositoryState state = stateService.getState(USER, "draft");
        assertFalse(state.projectionStale(), "No projection recorded → not stale");
    }

    @Test
    void projectionNotStaleWhenRecordedAtHead() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordProjection(USER, commitId, "draft");

        RepositoryState state = stateService.getState(USER, "draft");
        assertFalse(state.projectionStale());
        assertEquals(commitId, state.projectionCommit());
        assertEquals("draft", state.projectionBranch());
        assertNotNull(state.projectionTimestamp());
    }

    @Test
    void projectionStaleAfterNewCommit() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        stateService.recordProjection(USER, first, "draft");

        // New commit moves HEAD ahead of projection
        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");

        RepositoryState state = stateService.getState(USER, "draft");
        assertTrue(state.projectionStale(), "HEAD moved → projection is stale");
    }

    @Test
    void isProjectionStaleBranch() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        stateService.recordProjection(USER, first, "draft");

        assertFalse(stateService.isProjectionStale(USER, "draft"));

        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");
        assertTrue(stateService.isProjectionStale(USER, "draft"));
    }

    // ── Index tracking ──────────────────────────────────────────────

    @Test
    void indexNotStaleWhenNoIndexRecorded() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        RepositoryState state = stateService.getState(USER, "draft");
        assertFalse(state.indexStale(), "No index recorded → not stale");
    }

    @Test
    void indexNotStaleWhenRecordedAtHead() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordIndexBuild(USER, commitId);

        RepositoryState state = stateService.getState(USER, "draft");
        assertFalse(state.indexStale());
        assertEquals(commitId, state.indexCommit());
    }

    @Test
    void indexStaleAfterNewCommit() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        stateService.recordIndexBuild(USER, first);

        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");

        RepositoryState state = stateService.getState(USER, "draft");
        assertTrue(state.indexStale(), "HEAD moved → index is stale");
    }

    // ── Operation tracking ──────────────────────────────────────────

    @Test
    void noOperationByDefault() {
        RepositoryState state = stateService.getState(USER, "draft");
        assertFalse(state.operationInProgress());
        assertNull(state.operationKind());
    }

    @Test
    void operationInProgressAfterBegin() {
        stateService.beginOperation(USER, "merge");

        RepositoryState state = stateService.getState(USER, "draft");
        assertTrue(state.operationInProgress());
        assertEquals("merge", state.operationKind());
    }

    @Test
    void operationClearedAfterEnd() {
        stateService.beginOperation(USER, "cherry-pick");
        stateService.endOperation(USER);

        RepositoryState state = stateService.getState(USER, "draft");
        assertFalse(state.operationInProgress());
        assertNull(state.operationKind());
    }

    // ── getViewContext ──────────────────────────────────────────────

    @Test
    void getViewContextReturnsMetadata() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordProjection(USER, commitId, "draft");
        stateService.recordIndexBuild(USER, commitId);

        ViewContext ctx = stateService.getViewContext(USER, "draft");

        assertEquals(commitId, ctx.basedOnCommit());
        assertEquals("draft", ctx.basedOnBranch());
        assertNotNull(ctx.commitTimestamp());
        assertFalse(ctx.projectionStale());
        assertFalse(ctx.indexStale());
    }

    @Test
    void getViewContextOnEmptyBranch() {
        ViewContext ctx = stateService.getViewContext(USER, "nonexistent");
        assertNull(ctx.basedOnCommit());
        assertEquals("nonexistent", ctx.basedOnBranch());
    }

    // ── getProjectionState ──────────────────────────────────────────

    @Test
    void getProjectionStateReturnsFullDiagnostics() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordProjection(USER, commitId, "draft");
        stateService.recordIndexBuild(USER, commitId);

        ProjectionState ps = stateService.getProjectionState(USER, "draft");

        assertEquals(commitId, ps.projectionCommit());
        assertEquals("draft", ps.projectionBranch());
        assertNotNull(ps.projectionTimestamp());
        assertEquals(commitId, ps.indexCommit());
        assertNotNull(ps.indexTimestamp());
        assertFalse(ps.projectionStale());
        assertFalse(ps.indexStale());
    }

    @Test
    void getProjectionStateDetectsStaleness() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        stateService.recordProjection(USER, first, "draft");
        stateService.recordIndexBuild(USER, first);

        // Move HEAD ahead
        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");

        ProjectionState ps = stateService.getProjectionState(USER, "draft");
        assertTrue(ps.projectionStale());
        assertTrue(ps.indexStale());
    }
}
