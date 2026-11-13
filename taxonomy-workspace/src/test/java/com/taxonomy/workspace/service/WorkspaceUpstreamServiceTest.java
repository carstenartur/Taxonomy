package com.taxonomy.workspace.service;

import com.taxonomy.workspace.controller.WorkspaceUpstreamController;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
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
        database = new WorkspaceFetchJournalTest.Database();
        actors = mock(WorkspaceFetchAuthority.class);
        when(actors.authorize("alice", RepositoryScope.WORKSPACE)).thenReturn(WorkspaceFetchJournalTest.GRANT);
        when(actors.currentActor("alice")).thenReturn(WorkspaceFetchJournalTest.ALICE);
        recovery = new WorkspaceFetchRecovery(database.journal, service, actors, null);
        mvc = MockMvcBuilders.standaloneSetup(new WorkspaceUpstreamController(service, resolver, recovery)).build();
    }

    @AfterEach void close() { if (database != null) database.close(); repositories.close(); }

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
