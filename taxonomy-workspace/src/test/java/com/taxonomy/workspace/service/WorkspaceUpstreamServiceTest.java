package com.taxonomy.workspace.service;

import com.taxonomy.workspace.controller.WorkspaceUpstreamController;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevWalk;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkspaceUpstreamServiceTest {
    private final RepositoryContext selected = RepositoryContext.workspace("source", "w1", "draft", "alice");
    private final UserWorkspaceRepository rows = mock(UserWorkspaceRepository.class);
    private final RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
    private final SystemRepository metadata = new SystemRepository();
    private final UserWorkspace workspace = new UserWorkspace();
    private final DslGitRepositoryFactory repositories = new DslGitRepositoryFactory(null);
    private final GitUpstreamRef tracked = new GitUpstreamRef("source", "main");
    private DslGitRepository source;
    private DslGitRepository target;
    private WorkspaceUpstreamService service;
    private MockMvc mvc;
    private WorkspaceFetchJournalTest.Database database;
    private WorkspaceFetchRecovery recovery;
    private WorkspaceFetchAuthority actors;
    private String base;
    private String incoming;
    private String local;

    @BeforeEach
    void prepare() throws Exception {
        workspace.setWorkspaceId("w1");
        workspace.setUsername("alice");
        workspace.setSourceBranch("main");
        workspace.setSourceRepositoryId("source");
        workspace.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
        source = repositories.getCentralRepository("source");
        target = repositories.openWorkspaceRepository("w1");
        base = source.commitDsl("main", "base", "owner", "Base");
        incoming = source.commitDsl("main", "new upstream", "owner", "Advance upstream");
        local = target.commitDsl("draft", "local edits", "alice", "Retained local work");
        workspace.setBaseCommit(base);
        workspace.setCurrentCommit(local);
        workspace.setLastIntegratedCommit(base);
        when(rows.findByWorkspaceId("w1")).thenReturn(Optional.of(workspace));
        when(rows.findByWorkspaceIdForUpdate("w1")).thenReturn(Optional.of(workspace));
        var catalogue = mock(SystemRepositoryService.class);
        when(catalogue.getRepository("source")).thenReturn(metadata);
        doAnswer(call -> {
            call.<Consumer<SystemRepository>>getArgument(1).accept(metadata);
            return null;
        }).when(catalogue).readCurrentAuthorization(eq("source"), any());
        when(memberships.canContribute(metadata, "alice")).thenReturn(true);
        service = new WorkspaceUpstreamService(rows, catalogue, memberships, repositories);
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(selected);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected);
        database = new WorkspaceFetchJournalTest.Database();
        actors = mock(WorkspaceFetchAuthority.class);
        when(actors.authorize("alice", RepositoryScope.WORKSPACE)).thenReturn(WorkspaceFetchJournalTest.GRANT);
        when(actors.currentActor("alice")).thenReturn(WorkspaceFetchJournalTest.ALICE);
        recovery = new WorkspaceFetchRecovery(database.journal, service, actors, null);
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceUpstreamController(service, resolver, recovery)).build();
    }

    @AfterEach void close() { if (database != null) database.close(); repositories.close(); }

    @Test
    void planValidatorTracksIndependentLocalSourceAndTrackingRefMoves() throws Exception {
        service.fetch(selected, new WorkspaceUpstreamService.Request(incoming, null));
        clearInvocations(rows);
        String initial = rebasePlanTag();

        target.commitDsl("draft", "independent local edit", "alice", "Move the local branch");
        String movedLocal = rebasePlanTag();
        assertThat(movedLocal).isNotEqualTo(initial);

        String later = source.commitDsl("main", "independent upstream edit", "owner", "Move the source branch");
        String movedSource = rebasePlanTag();
        assertThat(movedSource).isNotEqualTo(movedLocal);

        GitHistoryTransfer.copyCommitClosure(source, target, later, List.of(incoming));
        tracked.advance(target, incoming, later, "owner");
        String movedTracking = rebasePlanTag();
        assertThat(movedTracking).isNotEqualTo(movedSource);
        assertThat(rebasePlanTag()).isEqualTo(movedTracking);
        assertThat(workspace.getCurrentCommit()).isEqualTo(local);
        assertThat(workspace.getLastFetchedCommit()).isEqualTo(incoming);
        verify(rows, never()).save(any());
    }

    private static final String BRANCH = "feature";
    private static final String REBASE_PATH = "/api/workspace/upstream/rebase";
    private final WorkspacePortfolioGitPort portfolio = mock(WorkspacePortfolioGitPort.class);
    private final WorkspaceArchitectureVersionPort editor = mock(WorkspaceArchitectureVersionPort.class, CALLS_REAL_METHODS);
    private final WorkspaceContext featureScope = new WorkspaceContext("alice", "w1", BRANCH, "source");
    private final UserWorkspaceState state = new UserWorkspaceState("alice", 12, BRANCH);
    private final RepositoryContext feature = RepositoryContext.workspace("source", "w1", BRANCH, "alice");
    private com.taxonomy.versioning.service.ContextNavigationService navigation;
    private String reviewedHead;
    private String requestBody;

    private void preparePersonalReplay(String changes) throws Exception {
        service.fetch(selected, new WorkspaceUpstreamService.Request(incoming, null));
        target.createBranchAtCommit(BRANCH, base);
        reviewedHead = target.commitDsl(BRANCH, changes, "alice", "local change");
        workspace.setCurrentBranch(BRANCH);
        when(rows.lockSelectedBranch("w1", BRANCH)).thenAnswer(inv -> workspace.getCurrentBranch().equals(BRANCH) ? 1 : 0);
        workspace.setCurrentCommit(reviewedHead);
        var plan = service.rebasePlan(feature);
        assertThat(plan.status()).isEqualTo(UpstreamRebasePlan.State.REPLAY);
        requestBody = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                new UpstreamRebasePlan.ApplyRequest(plan.refTag(), reviewedHead, incoming, List.of(reviewedHead)));
        var manager = mock(WorkspaceManager.class);
        when(manager.getOrCreateScopedWorkspaceState(featureScope)).thenReturn(state);
        navigation = new com.taxonomy.versioning.service.ContextNavigationService(repositories, null, manager, 12);
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(feature);
        when(portfolio.lockedPublicationDigest("alice", featureScope)).thenReturn("initial projection");
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceUpstreamController(service, resolver, recovery),
                new com.taxonomy.workspace.controller.PersonalWorkspaceRebaseController(resolver, navigation,
                        new PersonalWorkspaceRebase(service, editor, portfolio))).build();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder personalPost(String path, String body) {
        return post(path).param("repositoryId", "source").param("workspaceId", "w1").param("branch", BRANCH)
                .param("expectedContextId", state.legacyNavigation().getCurrentContext().contextId())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void personalPostAdoptsExactlyTheReviewedReplayAndRetainsOtherBranches() throws Exception {
        preparePersonalReplay("base");
        mvc.perform(personalPost(REBASE_PATH, requestBody)).andExpect(status().isOk())
                .andExpect(jsonPath("$.originalHead").value(reviewedHead))
                .andExpect(jsonPath("$.rewritten[0].originalCommit").value(reviewedHead));
        var newHead = target.getHeadCommit(BRANCH);
        assertThat(newHead).isNotEqualTo(reviewedHead).isEqualTo(workspace.getCurrentCommit());
        assertThat(workspace.getLastIntegratedCommit()).isEqualTo(incoming);
        assertThat(workspace.getLastFetchedCommit()).isEqualTo(incoming);
        assertThat(workspace.getBaseCommit()).isEqualTo(base);
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
        assertThat(source.getHeadCommit("main")).isEqualTo(incoming);
        assertThat(tracked.head(target)).isEqualTo(incoming);
        verify(portfolio).materializePortfolio("new upstream", "alice", featureScope);
        verify(rows, atLeastOnce()).findByWorkspaceIdForUpdate("w1");
        verify(memberships).lockForRefMutation("source");
    }

    @Test
    void personalConflictReturnsThreeVersionsAndAcceptsTheReviewedResolution() throws Exception {
        preparePersonalReplay("local text");
        var result = mvc.perform(personalPost(REBASE_PATH, requestBody)).andExpect(status().isOk())
                .andExpect(jsonPath("$.conflictCommit").value(reviewedHead)).andExpect(jsonPath("$.dslConflict.source.text").value("local text"))
                .andReturn();
        assertThat(target.getHeadCommit(BRANCH)).isEqualTo(reviewedHead);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var blob = json.readTree(result.getResponse().getContentAsString()).at("/dslConflict/target/blobId").asText();
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(requestBody);
        body.putObject("resolutions").putObject(reviewedHead).put("targetBlobId", blob).put("text", "combined");
        mvc.perform(personalPost(REBASE_PATH, body.toString())).andExpect(status().isOk());
        assertThat(target.getDslAtCommit(workspace.getCurrentCommit())).isEqualTo("combined");
    }

    @Test
    void movedWorkspaceBranchOrBrowserSelectionRejectsTheReviewedPost() throws Exception {
        preparePersonalReplay("base");
        workspace.setCurrentBranch("draft");
        mvc.perform(personalPost(REBASE_PATH, requestBody)).andExpect(status().isConflict());
        workspace.setCurrentBranch(BRANCH);
        var prior = personalPost(REBASE_PATH, requestBody);
        navigation.switchContext("alice", BRANCH, null, featureScope);
        mvc.perform(prior).andExpect(status().isConflict());
        navigation.openReadOnly("alice", BRANCH, reviewedHead, featureScope, null, null);
        assertThatThrownBy(() -> mvc.perform(personalPost(REBASE_PATH, requestBody)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThat(target.getHeadCommit(BRANCH)).isEqualTo(reviewedHead);
    }

    @Test
    void localRefCompetitionPreservesTheCompetingWrite() throws Exception {
        preparePersonalReplay("base");
        doAnswer(inv -> { target.commitDsl(BRANCH, "new local", "alice", "independent"); return null; })
                .when(portfolio).validatePortfolio(anyString(), anyString(), eq("alice"), eq(featureScope));
        mvc.perform(personalPost(REBASE_PATH, requestBody)).andExpect(status().isConflict());
        assertThat(target.getDslAtCommit(target.getHeadCommit(BRANCH))).isEqualTo("new local");
        assertThat(workspace.getCurrentCommit()).isEqualTo(reviewedHead);
        assertThat(workspace.getLastIntegratedCommit()).isEqualTo(base);
    }

    @Test
    void personalPostRequiresCurrentSourceAuthorityAndOwnedReadyWorkingCopy() throws Exception {
        preparePersonalReplay("base");
        when(memberships.canContribute(metadata, "alice")).thenReturn(false);
        assertThatThrownBy(() -> mvc.perform(personalPost(REBASE_PATH, requestBody)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        when(memberships.canContribute(metadata, "alice")).thenReturn(true);
        workspace.setArchived(true);
        assertThatThrownBy(() -> mvc.perform(personalPost(REBASE_PATH, requestBody)))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        assertThat(target.getHeadCommit(BRANCH)).isEqualTo(reviewedHead);
    }

    @Test
    void upstreamMovementAfterReviewRequiresANewFetchAndPlan() throws Exception {
        preparePersonalReplay("base");
        source.commitDsl("main", "new upstream work", "owner", "independent");
        mvc.perform(personalPost(REBASE_PATH, requestBody)).andExpect(status().isConflict());
        assertThat(target.getHeadCommit(BRANCH)).isEqualTo(reviewedHead);
        assertThat(tracked.head(target)).isEqualTo(incoming);
    }

    @Test
    void lostSqlAdoptionCanFinishFromFreshPlanWithoutASecondReplay() throws Exception {
        preparePersonalReplay("base");
        doThrow(new IllegalStateException("Interrupted SQL materialization")).doNothing()
                .when(portfolio).materializePortfolio(anyString(), eq("alice"), eq(featureScope));
        assertThatThrownBy(() -> mvc.perform(personalPost(REBASE_PATH, requestBody)))
                .hasRootCauseMessage("Interrupted SQL materialization");
        String candidate = target.getHeadCommit(BRANCH);
        assertThat(candidate).isNotEqualTo(reviewedHead);
        assertThat(workspace.getCurrentCommit()).isEqualTo(reviewedHead);
        mvc.perform(get("/api/workspace/upstream/rebase-plan")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recoverableCommit").value(candidate));
        String resume = recovery(candidate, candidate);
        mvc.perform(personalPost(REBASE_PATH + "/resume", "{\"candidate\":\"" + reviewedHead + "\"}")).andExpect(status().isConflict());
        when(portfolio.lockedPublicationDigest("alice", featureScope)).thenReturn("concurrent portfolio edit");
        doThrow(new BranchHeadConflictException(BRANCH, null, null, "dirty portfolio")).when(portfolio)
                .requireCheckpointedPortfolio(anyString(), eq("alice"), eq(featureScope));
        mvc.perform(personalPost(REBASE_PATH + "/resume", resume)).andExpect(status().isConflict());
        when(portfolio.lockedPublicationDigest("alice", featureScope)).thenReturn("initial projection");
        mvc.perform(personalPost(REBASE_PATH + "/resume", resume)).andExpect(status().isOk())
                .andExpect(jsonPath("$.newHead").value(candidate));
        assertThat(workspace.getCurrentCommit()).isEqualTo(candidate);
        assertThat(workspace.getLastIntegratedCommit()).isEqualTo(incoming);
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
    }

    @Test
    void acknowledgedReceiptSurvivingCleanupAllowsANewReviewedRebaseAfterFurtherWork() throws Exception {
        preparePersonalReplay("base");
        mvc.perform(personalPost(REBASE_PATH, requestBody)).andExpect(status().isOk());
        var acknowledged = workspace.getCurrentCommit();
        // This fixture has no afterCommit callback. The same retained tag survives a cleanup interruption.
        assertThat(com.taxonomy.workspace.storage.PersonalRebaseReceipt.read(target, feature.repositoryWorkspaceScopeKey()))
                .isNotNull();
        String later = target.commitDsl(BRANCH, "new upstream", "alice", "later local work");
        workspace.setCurrentCommit(later);
        String nextUpstream = source.commitDsl("main", "second upstream", "owner", "later source");
        service.fetch(selected, new WorkspaceUpstreamService.Request(nextUpstream, incoming));
        var plan = service.rebasePlan(feature);
        assertThat(plan.recoverableCommit()).isNull();
        var request = new UpstreamRebasePlan.ApplyRequest(plan.refTag(), later, nextUpstream,
                plan.commits().stream().map(UpstreamRebasePlan.Commit::id).toList());
        mvc.perform(personalPost(REBASE_PATH, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isOk());
        assertThat(workspace.getCurrentCommit()).isNotIn(later, acknowledged);
        assertThat(workspace.getLastIntegratedCommit()).isEqualTo(nextUpstream);
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void anUnsettledReplayAtADifferentHeadRequiresExplicitAdoptionOfThatExactHead(boolean includesReplay) throws Exception {
        preparePersonalReplay("base");
        doThrow(new IllegalStateException("interrupted")).doNothing().when(portfolio)
                .materializePortfolio(anyString(), eq("alice"), eq(featureScope));
        assertThatThrownBy(() -> mvc.perform(personalPost(REBASE_PATH, requestBody))).hasRootCauseMessage("interrupted");
        var retained = target.getHeadCommit(BRANCH);
        String advanced;
        if (includesReplay) advanced = target.commitDsl(BRANCH, "later branch", "alice", "independent follow-up");
        else {
            var replacement = target.getGitRepository().updateRef(Constants.R_HEADS + BRANCH);
            replacement.setExpectedOldObjectId(ObjectId.fromString(retained));
            replacement.setNewObjectId(ObjectId.fromString(local));
            replacement.setForceUpdate(true);
            assertThat(replacement.update()).isEqualTo(org.eclipse.jgit.lib.RefUpdate.Result.FORCED);
            advanced = local;
        }
        mvc.perform(get("/api/workspace/upstream/rebase-plan")).andExpect(status().isOk())
                .andExpect(jsonPath("$.recoverableCommit").value(retained)).andExpect(jsonPath("$.localCommit").value(advanced));
        mvc.perform(personalPost(REBASE_PATH, requestBody)).andExpect(status().isConflict());
        mvc.perform(personalPost(REBASE_PATH + "/resume", recovery(retained, retained))).andExpect(status().isConflict());
        mvc.perform(personalPost(REBASE_PATH + "/resume", recovery(retained, advanced))).andExpect(status().isOk())
                .andExpect(jsonPath("$.newHead").value(advanced));
        assertThat(target.getHeadCommit(BRANCH)).isEqualTo(advanced);
        assertThat(workspace.getCurrentCommit()).isEqualTo(advanced);
        assertThat(workspace.getLastIntegratedCommit()).isEqualTo(includesReplay ? incoming : base);
    }

    private static String recovery(String candidate, String head) {
        return "{\"candidate\":\"" + candidate + "\",\"expectedHead\":\"" + head + "\"}";
    }

    private String rebasePlanTag() throws Exception {
        String path = "/api/workspace/upstream/rebase-plan";
        String tag = mvc.perform(get(path)).andExpect(status().isOk())
                .andExpect(header().exists("ETag")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(head(path)).andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("ETag", tag));
        return tag;
    }

    @Test
    void planUsesOnlyTheCapturedTrackingCommitAndListsLocalCommitsOldestFirst() throws Exception {
        service.fetch(selected, new WorkspaceUpstreamService.Request(incoming, null));
        target.createBranchAtCommit("feature", base);
        String first = target.commitDsl("feature", "local one", "alice", "one");
        String second = target.commitDsl("feature", "local two", "alice", "two");
        String later = source.commitDsl("main", "later", "owner", "Later upstream");
        var feature = RepositoryContext.workspace("source", "w1", "feature", "alice");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(feature);
        var api = MockMvcBuilders.standaloneSetup(new WorkspaceUpstreamController(service, resolver, recovery)).build();
        api.perform(get("/api/workspace/upstream/rebase-plan")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("REPLAY"))
                .andExpect(jsonPath("$.repositoryId").value("source")).andExpect(jsonPath("$.workspaceId").value("w1"))
                .andExpect(jsonPath("$.branch").value("feature")).andExpect(jsonPath("$.localCommit").value(second))
                .andExpect(jsonPath("$.upstream.sourceCommit").value(later))
                .andExpect(jsonPath("$.upstream.trackedCommit").value(incoming))
                .andExpect(jsonPath("$.mergeBase").value(base))
                .andExpect(jsonPath("$.commits[0].id").value(first)).andExpect(jsonPath("$.commits[1].id").value(second));
        assertThat(target.getHeadCommit("feature")).isEqualTo(second);
        assertThat(tracked.head(target)).isEqualTo(incoming);
        assertThat(source.getHeadCommit("main")).isEqualTo(later);
    }

    @Test
    void planDoesNotFetchACommitOrBorrowAnotherBranchsHistory() throws Exception {
        mvc.perform(get("/api/workspace/upstream/rebase-plan")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_TRACKING")).andExpect(jsonPath("$.commits").isEmpty());
        assertThat(tracked.head(target)).isNull();
        assertThat(target.getGitRepository().getObjectDatabase().has(ObjectId.fromString(incoming))).isFalse();
        service.fetch(selected, new WorkspaceUpstreamService.Request(incoming, null));
        mvc.perform(get("/api/workspace/upstream/rebase-plan")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNRELATED")).andExpect(jsonPath("$.commits").isEmpty());
        when(memberships.canContribute(metadata, "alice")).thenReturn(false);
        assertThatThrownBy(() -> mvc.perform(get("/api/workspace/upstream/rebase-plan")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    void duplicateFetchPostReturnsTheOriginalReceiptDuringBackoffAndExplicitRetryCanResumeIt() throws Exception {
        when(rows.save(workspace)).thenThrow(new IllegalStateException("Lost database acknowledgement")).thenReturn(workspace);
        String body = "{\"sourceCommit\":\"" + incoming + "\",\"expectedTrackedCommit\":null}";
        var first = mvc.perform(post("/api/workspace/upstream/fetch").header("Idempotency-Key", "backoff")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn();
        String id = first.getResponse().getHeader("X-Operation-Id");
        assertThat(id).isNotNull();
        assertThat(tracked.head(target)).isEqualTo(incoming);
        for (int replay = 0; replay < 6; replay++) {
            mvc.perform(post("/api/workspace/upstream/fetch").header("Idempotency-Key", "backoff")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted()).andExpect(header().string("X-Operation-Id", id));
        }
        mvc.perform(get("/api/workspace/upstream/operations/{id}", id))
                .andExpect(jsonPath("$.phase").value("RETRYABLE")).andExpect(jsonPath("$.attempts").value(1));
        verify(rows, times(1)).save(workspace);
        mvc.perform(post("/api/workspace/upstream/operations/{id}/retry", id))
                .andExpect(status().isOk()).andExpect(header().string("X-Operation-Id", id))
                .andExpect(jsonPath("$.trackedCommit").value(incoming));
        mvc.perform(get("/api/workspace/upstream/operations/{id}", id))
                .andExpect(jsonPath("$.phase").value("COMPLETED")).andExpect(jsonPath("$.attempts").value(2));
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
    }

    @Test
    void routeFetchesOnlyTheReviewedSourceAndRetainsBothWorkingAndIntegrationHeads() throws Exception {
        mvc.perform(get("/api/workspace/upstream"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceCommit").value(incoming))
                .andExpect(jsonPath("$.trackedCommit").isEmpty());
        String request = "{\"sourceCommit\":\"" + incoming + "\",\"expectedTrackedCommit\":null}";
        String originalOperation = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = mvc.perform(post("/api/workspace/upstream/fetch").contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.trackedCommit").value(incoming))
                    .andExpect(header().exists("X-Operation-Id")).andReturn();
            String operation = result.getResponse().getHeader("X-Operation-Id");
            if (originalOperation == null) originalOperation = operation;
            assertThat(operation).isEqualTo(originalOperation);
            mvc.perform(get("/api/workspace/upstream/operations/{id}", operation)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.phase").value("COMPLETED"))
                    .andExpect(jsonPath("$.requestedCommit").value(incoming));
        }
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
        assertThat(workspace.getCurrentCommit()).isEqualTo(local);
        assertThat(workspace.getLastIntegratedCommit()).isEqualTo(base);
        assertThat(workspace.getLastFetchedCommit()).isEqualTo(incoming);
        assertThat(target.getDslAtCommit(base)).isEqualTo("base");
        mvc.perform(post("/api/workspace/upstream/fetch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCommit\":\"" + base + "\",\"expectedTrackedCommit\":null}"))
                .andExpect(status().isConflict());
        assertThat(tracked.head(target)).isEqualTo(incoming);
    }

    @Test
    void boundWorkingCopyFetchUsesTheFencedOriginalActor() throws Exception {
        var original = java.util.UUID.fromString(WorkspaceFetchJournalTest.ALICE);
        workspace.assignOwnerPrincipal(original);
        var request = new WorkspaceUpstreamService.Request(incoming, null);
        var fence = mock(Runnable.class);
        assertThatThrownBy(() -> service.fetchRecorded(selected, "main", base, request, true,
                fence, java.util.UUID.randomUUID().toString())).isInstanceOf(AccessDeniedException.class);
        verify(fence).run();
        assertThat(tracked.head(target)).isNull();
        verify(rows, never()).save(any());

        assertThat(service.fetchRecorded(selected, "main", base, request, true, fence, original.toString())
                .trackedCommit()).isEqualTo(incoming);
        assertThat(tracked.head(target)).isEqualTo(incoming);
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
    }

    @Test
    void upstreamUsesTheConfiguredSyncBranchAndTheCatalogueDefaultForOlderWorkingCopies() throws Exception {
        workspace.setSourceBranch(null);
        metadata.setDefaultBranch("main");
        assertThat(service.inspect(selected).sourceCommit()).isEqualTo(incoming);
        source.createBranchAtCommit("review", base);
        workspace.setSyncTargetBranch("review");
        assertThat(service.inspect(selected).sourceBranch()).isEqualTo("review");
        assertThat(service.fetch(selected, new WorkspaceUpstreamService.Request(base, null)).trackedCommit()).isEqualTo(base);
        assertThat(tracked.head(target)).isNull();
    }

    @Test
    void failedMetadataWriteCanRetryTheSameCapturedCommitAfterSourceAdvancement() throws Exception {
        var request = new WorkspaceUpstreamService.Request(incoming, null);
        when(rows.save(workspace)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(workspace);
        assertThatThrownBy(() -> service.fetch(selected, request)).isInstanceOf(IllegalStateException.class);
        assertThat(tracked.head(target)).isEqualTo(incoming);
        workspace.setLastFetchedCommit(null);
        String later = source.commitDsl("main", "later", "owner", "After interruption");
        var result = service.fetch(selected, request);
        assertThat(result.sourceCommit()).isEqualTo(later);
        assertThat(tracked.head(target)).isEqualTo(incoming);
        assertThat(workspace.getLastFetchedCommit()).isEqualTo(incoming);
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
    }

    @Test
    void revokedMembershipPreventsTrackingRefPublication() throws Exception {
        when(memberships.canContribute(metadata, "alice")).thenReturn(true, false);
        assertThatThrownBy(() -> service.fetch(selected, new WorkspaceUpstreamService.Request(incoming, null)))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(tracked.head(target)).isNull();
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
        verify(rows, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void replayRejectsASelectedTagReplacedDuringFinalAuthorization(boolean recovering) throws Exception {
        service.fetch(selected, new WorkspaceUpstreamService.Request(incoming, null));
        String name = "review/interrupted";
        try (var walk = new RevWalk(source.getGitRepository())) {
            Git.wrap(source.getGitRepository()).tag().setName(name).setAnnotated(false)
                    .setObjectId(walk.parseCommit(ObjectId.fromString(base))).call();
        }
        var request = new WorkspaceUpstreamService.TagRequest(name, base, incoming);
        service.fetchTag(selected, request);
        var transfer = new GitUpstreamTransfer(tracked, source, target, base, "alice", () -> {
            try (var walk = new RevWalk(target.getGitRepository())) {
                Git.wrap(target.getGitRepository()).tag().setName(name).setAnnotated(false).setForceUpdate(true)
                        .setObjectId(walk.parseCommit(ObjectId.fromString(local))).call();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertThatThrownBy(() -> transfer.fetchTag(request, recovering, () -> { }))
                .isInstanceOf(BranchHeadConflictException.class);
        assertThat(target.readFreshRef(Constants.R_TAGS + name).getObjectId().name()).isEqualTo(local);
        assertThat(tracked.head(target)).isEqualTo(incoming);
        assertThat(target.getHeadCommit("draft")).isEqualTo(local);
    }

    @Test
    void selectedLightweightAndAnnotatedTagsRetainExactObjectsWithoutOverwritingOtherTags() throws Exception {
        service.fetch(selected, new WorkspaceUpstreamService.Request(incoming, null));
        try (var walk = new RevWalk(source.getGitRepository())) {
            var commit = walk.parseCommit(ObjectId.fromString(base));
            var api = Git.wrap(source.getGitRepository());
            api.tag().setName("light").setAnnotated(false).setObjectId(commit).call();
            var annotated = api.tag().setName("release/v1").setAnnotated(true).setMessage("Review checkpoint")
                    .setTagger(new PersonIdent("alice", "alice@example.test")).setObjectId(commit).call();
            var status = service.inspectTag(selected, "release/v1");
            assertThat(status.sourceTagObject()).isEqualTo(annotated.getObjectId().name());
            mvc.perform(get("/api/workspace/upstream/tag").param("name", "release/v1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.sourceTagObject").value(status.sourceTagObject()));
            var selection = new WorkspaceUpstreamService.TagRequest("release/v1", status.sourceTagObject(), incoming);
            service.fetchTag(selected, selection);
            mvc.perform(post("/api/workspace/upstream/fetch-tag").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"release/v1\",\"sourceTagObject\":\"" + status.sourceTagObject()
                                    + "\",\"trackedCommit\":\"" + incoming + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.destinationTagObject").value(status.sourceTagObject()));
            assertThat(target.getGitRepository().open(annotated.getObjectId(), Constants.OBJ_TAG).getBytes())
                    .isEqualTo(source.getGitRepository().open(annotated.getObjectId(), Constants.OBJ_TAG).getBytes());
            assertThat(target.getGitRepository().exactRef("refs/tags/light")).isNull();
            service.fetchTag(selected, new WorkspaceUpstreamService.TagRequest("light", base, incoming));
            assertThat(target.getGitRepository().exactRef("refs/tags/light").getObjectId().name()).isEqualTo(base);
            api.tag().setName("light").setForceUpdate(true).setAnnotated(false)
                    .setObjectId(walk.parseCommit(ObjectId.fromString(incoming))).call();
            assertThatThrownBy(() -> service.fetchTag(selected, new WorkspaceUpstreamService.TagRequest(
                    "light", incoming, incoming))).isInstanceOf(BranchHeadConflictException.class);
            assertThat(target.getGitRepository().exactRef("refs/tags/light").getObjectId().name()).isEqualTo(base);
            assertThat(target.getHeadCommit("draft")).isEqualTo(local);
        }
    }

    @Test
    void archivedOrForeignWorkingCopiesAreRejectedBeforeFetch() {
        workspace.setArchived(true);
        var request = new WorkspaceUpstreamService.Request(incoming, null);
        assertThatThrownBy(() -> service.fetch(selected, request)).isInstanceOf(AccessDeniedException.class);
        workspace.setArchived(false);
        workspace.setUsername("other");
        assertThatThrownBy(() -> service.fetch(selected, request)).isInstanceOf(AccessDeniedException.class);
        verify(rows, never()).save(any());
    }
}
