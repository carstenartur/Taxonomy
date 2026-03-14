package com.taxonomy.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dto.ProjectionState;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.dto.ViewContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RepositoryStateService}.
 *
 * <p>Uses an in-memory DslGitRepository — no Spring context required.
 */
class RepositoryStateServiceTest {

    private DslGitRepository gitRepo;
    private RepositoryStateService stateService;

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
        stateService = new RepositoryStateService(gitRepo);
    }

    // ── getState ────────────────────────────────────────────────────

    @Test
    void getStateOnEmptyBranchReturnsDefaults() {
        RepositoryState state = stateService.getState("draft");

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

        RepositoryState state = stateService.getState("draft");

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

        RepositoryState state = stateService.getState("draft");

        assertEquals(2, state.branches().size());
        assertTrue(state.branches().contains("draft"));
        assertTrue(state.branches().contains("review"));
    }

    // ── Projection tracking ─────────────────────────────────────────

    @Test
    void projectionNotStaleWhenNoProjectionRecorded() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        RepositoryState state = stateService.getState("draft");
        assertFalse(state.projectionStale(), "No projection recorded → not stale");
    }

    @Test
    void projectionNotStaleWhenRecordedAtHead() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordProjection(commitId, "draft");

        RepositoryState state = stateService.getState("draft");
        assertFalse(state.projectionStale());
        assertEquals(commitId, state.projectionCommit());
        assertEquals("draft", state.projectionBranch());
        assertNotNull(state.projectionTimestamp());
    }

    @Test
    void projectionStaleAfterNewCommit() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        stateService.recordProjection(first, "draft");

        // New commit moves HEAD ahead of projection
        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");

        RepositoryState state = stateService.getState("draft");
        assertTrue(state.projectionStale(), "HEAD moved → projection is stale");
    }

    @Test
    void isProjectionStaleBranch() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        stateService.recordProjection(first, "draft");

        assertFalse(stateService.isProjectionStale("draft"));

        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");
        assertTrue(stateService.isProjectionStale("draft"));
    }

    // ── Index tracking ──────────────────────────────────────────────

    @Test
    void indexNotStaleWhenNoIndexRecorded() throws IOException {
        gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        RepositoryState state = stateService.getState("draft");
        assertFalse(state.indexStale(), "No index recorded → not stale");
    }

    @Test
    void indexNotStaleWhenRecordedAtHead() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordIndexBuild(commitId);

        RepositoryState state = stateService.getState("draft");
        assertFalse(state.indexStale());
        assertEquals(commitId, state.indexCommit());
    }

    @Test
    void indexStaleAfterNewCommit() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "first");
        stateService.recordIndexBuild(first);

        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");

        RepositoryState state = stateService.getState("draft");
        assertTrue(state.indexStale(), "HEAD moved → index is stale");
    }

    // ── Operation tracking ──────────────────────────────────────────

    @Test
    void noOperationByDefault() {
        RepositoryState state = stateService.getState("draft");
        assertFalse(state.operationInProgress());
        assertNull(state.operationKind());
    }

    @Test
    void operationInProgressAfterBegin() {
        stateService.beginOperation("merge");

        RepositoryState state = stateService.getState("draft");
        assertTrue(state.operationInProgress());
        assertEquals("merge", state.operationKind());
    }

    @Test
    void operationClearedAfterEnd() {
        stateService.beginOperation("cherry-pick");
        stateService.endOperation();

        RepositoryState state = stateService.getState("draft");
        assertFalse(state.operationInProgress());
        assertNull(state.operationKind());
    }

    // ── getViewContext ──────────────────────────────────────────────

    @Test
    void getViewContextReturnsMetadata() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordProjection(commitId, "draft");
        stateService.recordIndexBuild(commitId);

        ViewContext ctx = stateService.getViewContext("draft");

        assertEquals(commitId, ctx.basedOnCommit());
        assertEquals("draft", ctx.basedOnBranch());
        assertNotNull(ctx.commitTimestamp());
        assertFalse(ctx.projectionStale());
        assertFalse(ctx.indexStale());
    }

    @Test
    void getViewContextOnEmptyBranch() {
        ViewContext ctx = stateService.getViewContext("nonexistent");
        assertNull(ctx.basedOnCommit());
        assertEquals("nonexistent", ctx.basedOnBranch());
    }

    // ── getProjectionState ──────────────────────────────────────────

    @Test
    void getProjectionStateReturnsFullDiagnostics() throws IOException {
        String commitId = gitRepo.commitDsl("draft", SAMPLE_DSL, "alice", "initial");
        stateService.recordProjection(commitId, "draft");
        stateService.recordIndexBuild(commitId);

        ProjectionState ps = stateService.getProjectionState("draft");

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
        stateService.recordProjection(first, "draft");
        stateService.recordIndexBuild(first);

        // Move HEAD ahead
        gitRepo.commitDsl("draft", SAMPLE_DSL, "bob", "second");

        ProjectionState ps = stateService.getProjectionState("draft");
        assertTrue(ps.projectionStale());
        assertTrue(ps.indexStale());
    }
}
