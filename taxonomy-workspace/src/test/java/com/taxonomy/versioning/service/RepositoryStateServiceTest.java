package com.taxonomy.versioning.service;

import com.taxonomy.versioning.controller.GitStateController;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.UserWorkspaceState;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ProjectionState;
import com.taxonomy.dto.RepositoryReadiness;
import com.taxonomy.dto.RepositoryState;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
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

    @Test
    void aSelectedViewReadsItsOwnBranchAndWorkspaceProgressWithoutAllocatingState() throws IOException {
        var factory = mock(DslGitRepositoryFactory.class);
        var navigation = mock(WorkspaceManager.class);
        var selected = new com.taxonomy.workspace.service.WorkspaceContext("reader", "work-B", "review", "repo-B");
        var progress = new com.taxonomy.workspace.service.UserWorkspaceState("reader", 10);
        String head = gitRepo.commitDsl("review", SAMPLE_DSL, "author", "selected review");
        progress.recordProjection(head, "review");
        progress.recordIndexBuild("e".repeat(40));
        org.mockito.Mockito.when(factory.resolveRepository(selected)).thenReturn(gitRepo);
        org.mockito.Mockito.when(navigation.getScopedWorkspaceState(selected)).thenReturn(progress);
        var states = new RepositoryStateService(factory, navigation, mock(com.taxonomy.workspace.service.SystemRepositoryService.class));

        var version = states.readSelectedViewContext(selected).orElseThrow();
        assertEquals(head, version.basedOnCommit());
        assertEquals("review", version.basedOnBranch());
        assertNotNull(version.commitTimestamp());
        assertFalse(version.projectionStale());
        assertTrue(version.indexStale());
        org.mockito.Mockito.verify(navigation).getScopedWorkspaceState(selected);
        org.mockito.Mockito.verifyNoMoreInteractions(navigation);
    }


    @Test
    void contextualMetadataUsesOnlySelectedProgressAcrossAllPublicReadPaths() throws IOException {
        var selected = new WorkspaceContext(USER, "work-B", "review", "repo-B");
        var factory = mock(DslGitRepositoryFactory.class);
        var navigation = mock(WorkspaceManager.class);
        String head = gitRepo.commitDsl("review", SAMPLE_DSL, USER, "selected review");
        when(factory.resolveRepository(selected)).thenReturn(gitRepo);

        var active = new UserWorkspaceState(USER, 10);
        active.recordProjection("b".repeat(40), "draft");
        active.recordIndexBuild("b".repeat(40));
        active.beginOperation("merge");
        when(navigation.getOrCreateWorkspace(USER)).thenReturn(active);

        var progress = new UserWorkspaceState(USER, 10);
        progress.recordProjection(head, "review");
        progress.recordIndexBuild(head);
        when(navigation.getScopedWorkspaceState(selected)).thenReturn(progress);
        var service = new RepositoryStateService(factory, navigation, mock(SystemRepositoryService.class));

        RepositoryState state = service.getState(USER, "review", selected);
        assertEquals(head, state.projectionCommit());
        assertEquals("review", state.projectionBranch());
        assertEquals(head, state.indexCommit());
        assertFalse(state.operationInProgress());
        assertNull(state.operationKind());
        assertFalse(state.projectionStale());
        assertFalse(state.indexStale());
        assertEquals(RepositoryReadiness.UNVERIFIED, state.projectionReadiness());
        assertEquals(RepositoryReadiness.UNVERIFIED, state.indexReadiness());

        ViewContext context = service.getViewContext(USER, "review", selected);
        assertFalse(context.projectionStale());
        assertFalse(context.indexStale());
        assertEquals(RepositoryReadiness.UNVERIFIED, context.projectionReadiness());
        assertEquals(RepositoryReadiness.UNVERIFIED, context.indexReadiness());

        ProjectionState diagnostics = service.getProjectionState(USER, "review", selected);
        assertEquals(head, diagnostics.projectionCommit());
        assertEquals(head, diagnostics.indexCommit());
        assertFalse(diagnostics.projectionStale());
        assertFalse(diagnostics.indexStale());
        assertFalse(service.isProjectionStale(USER, "review", selected));
        verify(navigation, org.mockito.Mockito.never()).getOrCreateWorkspace(USER);
    }

    @Test
    void unavailableSelectedProgressCannotInheritAnActiveCopyOperationOrCheckpoint() throws IOException {
        var selected = new WorkspaceContext(USER, "not-loaded", "review", "repo-B");
        var factory = mock(DslGitRepositoryFactory.class);
        var navigation = mock(WorkspaceManager.class);
        gitRepo.commitDsl("review", SAMPLE_DSL, USER, "selected review");
        when(factory.resolveRepository(selected)).thenReturn(gitRepo);
        var active = new UserWorkspaceState(USER, 10);
        active.recordProjection("b".repeat(40), "draft");
        active.recordIndexBuild("b".repeat(40));
        active.beginOperation("merge");
        when(navigation.getOrCreateWorkspace(USER)).thenReturn(active);
        var service = new RepositoryStateService(factory, navigation, mock(SystemRepositoryService.class));

        RepositoryState state = service.getState(USER, "review", selected);
        assertNull(state.projectionCommit());
        assertNull(state.indexCommit());
        assertFalse(state.operationInProgress());
        assertFalse(state.projectionStale());
        assertFalse(state.indexStale());
        assertEquals(RepositoryReadiness.MISSING, state.projectionReadiness());
        assertEquals(RepositoryReadiness.MISSING, state.indexReadiness());

        ViewContext context = service.getViewContext(USER, "review", selected);
        assertFalse(context.projectionStale());
        assertFalse(context.indexStale());
        assertEquals(RepositoryReadiness.MISSING, context.projectionReadiness());
        assertEquals(RepositoryReadiness.MISSING, context.indexReadiness());

        ProjectionState diagnostics = service.getProjectionState(USER, "review", selected);
        assertNull(diagnostics.projectionCommit());
        assertNull(diagnostics.indexCommit());
        assertFalse(diagnostics.projectionStale());
        assertFalse(diagnostics.indexStale());
        assertFalse(service.isProjectionStale(USER, "review", selected));
        verify(navigation, org.mockito.Mockito.never()).getOrCreateWorkspace(USER);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"selected-copy", "uncached-copy"})
    void pinnedGitStateUsesOnlyProgressFromTheSelectedCopy(String workspaceId) throws IOException {
        var selected = new WorkspaceContext(USER, workspaceId, "review", "selected-repository");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.hasExplicitSourceSelection()).thenReturn(true);
        when(resolver.resolveUnambiguousContextForRead()).thenReturn(selected);
        var factory = mock(DslGitRepositoryFactory.class);
        when(factory.resolveRepository(selected)).thenReturn(gitRepo);
        String head = gitRepo.commitDsl("review", SAMPLE_DSL, USER, "selected review");

        var navigation = mock(WorkspaceManager.class);
        var active = new UserWorkspaceState(USER, 10);
        active.recordProjection("b".repeat(40), "draft");
        active.recordIndexBuild("b".repeat(40));
        active.beginOperation("merge");
        when(navigation.getOrCreateWorkspace(USER)).thenReturn(active);

        boolean hasProgress = "selected-copy".equals(workspaceId);
        if (hasProgress) {
            var progress = new UserWorkspaceState(USER, 10);
            progress.recordProjection(head, "review");
            progress.recordIndexBuild(head);
            when(navigation.getScopedWorkspaceState(selected)).thenReturn(progress);
        }

        var service = new RepositoryStateService(factory, navigation, mock(SystemRepositoryService.class));
        var state = new GitStateController(service, resolver).getState("review").getBody();

        assertNotNull(state);
        assertEquals(head, state.headCommit());
        assertEquals(hasProgress ? head : null, state.projectionCommit());
        assertEquals(hasProgress ? "review" : null, state.projectionBranch());
        assertEquals(hasProgress ? head : null, state.indexCommit());
        assertFalse(state.operationInProgress());
        assertNull(state.operationKind());
        assertEquals(hasProgress ? RepositoryReadiness.UNVERIFIED : RepositoryReadiness.MISSING,
                state.projectionReadiness());
        assertEquals(hasProgress ? RepositoryReadiness.UNVERIFIED : RepositoryReadiness.MISSING,
                state.indexReadiness());
        if (workspaceId != null) {
            verify(navigation).getScopedWorkspaceState(selected);
        }
        verifyNoMoreInteractions(navigation);
    }

    @Test
    void readinessUsesTheSelectedBranchAndDistinguishesAnUnavailableGitRead() throws IOException {
        var selected = new WorkspaceContext(USER, "work", "review", "repo");
        var factory = mock(DslGitRepositoryFactory.class);
        var navigation = mock(WorkspaceManager.class);
        var progress = new UserWorkspaceState(USER, 10);
        String first = gitRepo.commitDsl("review", SAMPLE_DSL, USER, "first");
        gitRepo.commitDsl("review", SAMPLE_DSL, USER, "second");
        when(factory.resolveRepository(selected)).thenReturn(gitRepo);
        when(navigation.getScopedWorkspaceState(selected)).thenReturn(progress);
        var service = new RepositoryStateService(factory, navigation, mock(SystemRepositoryService.class));

        progress.recordProjection(first, "draft");
        assertEquals(RepositoryReadiness.MISSING, service.readSelectedState(selected).projectionReadiness());
        progress.recordProjection(first, "review");
        assertEquals(RepositoryReadiness.STALE, service.readSelectedState(selected).projectionReadiness());
        assertEquals(RepositoryReadiness.STALE, service.readSelectedViewContext(selected).orElseThrow().projectionReadiness());

        var unavailable = mock(DslGitRepository.class);
        when(unavailable.getHeadCommitInfo("review")).thenThrow(new IOException("unavailable"));
        when(factory.resolveRepository(selected)).thenReturn(unavailable);
        var failed = service.readSelectedState(selected);
        assertEquals(RepositoryReadiness.FAILED, failed.projectionReadiness());
        assertEquals(RepositoryReadiness.FAILED, failed.indexReadiness());
        assertFalse(failed.projectionStale());
        assertFalse(failed.indexStale());
        var failedView = service.getViewContext(USER, "review", selected);
        assertEquals(RepositoryReadiness.FAILED, failedView.projectionReadiness());
        assertEquals(RepositoryReadiness.FAILED, failedView.indexReadiness());
    }

    // ── getState ────────────────────────────────────────────────────

    private static final String USER = "testuser";

    @Test
    void legacyActorOnlyCentralContextKeepsItsOwnOperationAndStaleCheckpoints() throws IOException {
        String first = gitRepo.commitDsl("draft", SAMPLE_DSL, USER, "first");
        stateService.recordProjection(USER, first, "draft");
        stateService.recordIndexBuild(USER, first);
        gitRepo.commitDsl("draft", SAMPLE_DSL, USER, "second");
        stateService.beginOperation(USER, "merge");

        // The actor-only guard builds this compatibility context, not SHARED
        // (which has the different actor "system"). Both must retain user state.
        var legacy = new WorkspaceContext(USER, null, "draft",
                WorkspaceContext.LEGACY_REPOSITORY_ID);
        var state = stateService.getState(USER, "draft", legacy);
        assertTrue(state.operationInProgress());
        assertEquals("merge", state.operationKind());
        assertEquals(first, state.projectionCommit());
        assertEquals(first, state.indexCommit());
        assertTrue(state.projectionStale());
        assertTrue(state.indexStale());

        var view = stateService.getViewContext(USER, "draft", legacy);
        assertTrue(view.projectionStale());
        assertTrue(view.indexStale());
        var projection = stateService.getProjectionState(USER, "draft", legacy);
        assertEquals(first, projection.projectionCommit());
        assertEquals(first, projection.indexCommit());
        assertTrue(projection.projectionStale());
        assertTrue(projection.indexStale());
        assertTrue(stateService.isProjectionStale(USER, "draft", legacy));
    }

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
