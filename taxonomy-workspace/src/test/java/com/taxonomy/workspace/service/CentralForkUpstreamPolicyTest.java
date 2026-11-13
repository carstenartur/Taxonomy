package com.taxonomy.workspace.service;

import com.taxonomy.workspace.controller.CentralForkUpstreamController;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.GitHistoryTransfer;
import com.taxonomy.workspace.storage.GitUpstreamRef;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CentralForkUpstreamPolicyTest {
    private static final String API = "/api/repositories/fork/upstream";
    private static final String ACTOR = "architect";
    private final SystemRepositoryService catalogue = mock(SystemRepositoryService.class);
    private final RepositoryMembershipService memberships = mock(RepositoryMembershipService.class);
    private final DslGitRepositoryFactory git = new DslGitRepositoryFactory(null);
    private final GitUpstreamRef tracking = new GitUpstreamRef("source", "main");
    private final SystemRepository origin = new SystemRepository();
    private final SystemRepository fork = new SystemRepository();
    private int forkReads;
    private Runnable duringTransfer = () -> {};
    private DslGitRepository source, target;
    private String base, incoming, local;
    private CentralForkUpstreamService service;
    private MockMvc mvc;
    private WorkspaceFetchJournalTest.Database database;
    private final WorkspaceFetchAuthority actors = mock(WorkspaceFetchAuthority.class);

    @BeforeEach
    void setUp() throws Exception {
        source = git.getCentralRepository("source");
        target = git.getCentralRepository("fork");
        base = source.commitDsl("main", "base", ACTOR, "Base");
        incoming = source.commitDsl("main", "next", ACTOR, "Next");
        GitHistoryTransfer.copyCommitClosure(source, target, base, List.of());
        tracking.advance(target, null, base, ACTOR);
        local = target.commitDsl("main", "independent", ACTOR, "Local");
        origin.setVisibility(RepositoryVisibility.PUBLIC);
        fork.setVisibility(RepositoryVisibility.PUBLIC);
        fork.setUpstreamRepositoryId("source");
        fork.setUpstreamBranch("main");
        fork.setForkPointCommit(base);
        when(memberships.canContribute(origin, ACTOR)).thenReturn(true);
        when(memberships.canContribute(fork, ACTOR)).thenReturn(true);
        doAnswer(call -> {
            if (++forkReads == 2) duringTransfer.run();
            return call.<BiFunction<SystemRepository, SystemRepository, ?>>getArgument(1).apply(fork, origin);
        }).when(catalogue).readCurrentForkAuthorization(eq("fork"), any());
        service = new CentralForkUpstreamService(catalogue, memberships, git);
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentUsername()).thenReturn(ACTOR);
        database = new WorkspaceFetchJournalTest.Database();
        when(actors.authorize(ACTOR, RepositoryScope.CENTRAL_WRITE)).thenReturn(WorkspaceFetchJournalTest.GRANT);
        when(actors.currentActor(ACTOR)).thenReturn(WorkspaceFetchJournalTest.ALICE);
        var recovery = new WorkspaceFetchRecovery(database.journal, null, actors, service);
        mvc = MockMvcBuilders.standaloneSetup(new CentralForkUpstreamController(service, resolver, recovery)).build();
    }

    @AfterEach void close() { if (database != null) database.close(); git.close(); }

    enum Change { SOURCE_ROLE, TARGET_ROLE, SOURCE_VISIBILITY, FORK_AUTHORITY }

    private void change(Change change) {
        switch (change) {
            case SOURCE_ROLE -> when(memberships.canContribute(origin, ACTOR)).thenReturn(false);
            case TARGET_ROLE -> when(memberships.canContribute(fork, ACTOR)).thenReturn(false);
            case SOURCE_VISIBILITY -> origin.setVisibility(RepositoryVisibility.ORGANIZATION);
            case FORK_AUTHORITY -> fork.setUpstreamBranch("other");
        }
    }

    @ParameterizedTest @EnumSource(Change.class)
    void changedAuthorityPreventsPublishingTheTrackingRef(Change change) throws Exception {
        duringTransfer = () -> change(change);
        var request = new WorkspaceUpstreamService.Request(incoming, base);
        assertThatThrownBy(() -> service.fetch("fork", ACTOR, request)).isInstanceOf(AccessDeniedException.class);
        assertThat(tracking.head(target)).isEqualTo(base);
        assertThat(target.getHeadCommit("main")).isEqualTo(local);
    }

    @ParameterizedTest @EnumSource(Change.class)
    void changedAuthorityPreventsPublishingTheSelectedTag(Change change) throws Exception {
        try (var walk = new RevWalk(source.getGitRepository())) {
            Git.wrap(source.getGitRepository()).tag().setName("review").setAnnotated(false)
                    .setObjectId(walk.parseCommit(ObjectId.fromString(base))).call();
        }
        duringTransfer = () -> change(change);
        var request = new WorkspaceUpstreamService.TagRequest("review", base, base);
        assertThatThrownBy(() -> service.fetchTag("fork", ACTOR, request)).isInstanceOf(AccessDeniedException.class);
        assertThat(target.getGitRepository().exactRef("refs/tags/review")).isNull();
        assertThat(tracking.head(target)).isEqualTo(base);
    }

    @Test
    void forkFetchAndSelectedTagHaveScopedDurableReceipts() throws Exception {
        String fetchBody = "{\"sourceCommit\":\"" + incoming + "\",\"expectedTrackedCommit\":\"" + base + "\"}";
        var commit = mvc.perform(post(API + "/fetch").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "first").content(fetchBody))
                .andExpect(status().isOk()).andExpect(header().exists("X-Operation-Id")).andReturn();
        String commitId = commit.getResponse().getHeader("X-Operation-Id");
        mvc.perform(get(API + "/operations/{id}", commitId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("COMPLETED"))
                .andExpect(jsonPath("$.repositoryId").value("fork"))
                .andExpect(jsonPath("$.sourceRepositoryId").value("source"))
                .andExpect(jsonPath("$.workspaceId").isEmpty())
                .andExpect(jsonPath("$.originalForkPoint").value(base))
                .andExpect(jsonPath("$.resultCommit").value(incoming));
        try (var walk = new RevWalk(source.getGitRepository())) {
            Git.wrap(source.getGitRepository()).tag().setName("review").setAnnotated(false)
                    .setObjectId(walk.parseCommit(ObjectId.fromString(base))).call();
        }
        String tagBody = "{\"name\":\"review\",\"sourceTagObject\":\"" + base
                + "\",\"trackedCommit\":\"" + incoming + "\"}";
        var tagged = mvc.perform(post(API + "/fetch-tag").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "selected").content(tagBody))
                .andExpect(status().isOk()).andExpect(header().exists("X-Operation-Id"))
                .andExpect(jsonPath("$.destinationTagObject").value(base)).andReturn();
        String tagId = tagged.getResponse().getHeader("X-Operation-Id");
        assertThat(tagId).isNotEqualTo(commitId);
        mvc.perform(get(API + "/operations/{id}", tagId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("COMPLETED"))
                .andExpect(jsonPath("$.selectedTag").value("review"))
                .andExpect(jsonPath("$.requestedTagObject").value(base))
                .andExpect(jsonPath("$.resultTagObject").value(base));
        mvc.perform(post(API + "/fetch-tag").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "selected").content(tagBody))
                .andExpect(status().isOk()).andExpect(header().string("X-Operation-Id", tagId));
        assertThat(target.getHeadCommit("main")).isEqualTo(local);
    }

    @Test
    void deferredForkFetchReturnsItsExistingReceiptAcrossDuplicatesRetryAndArchive() throws Exception {
        String request = "{\"sourceCommit\":\"" + incoming + "\",\"expectedTrackedCommit\":\"" + base + "\"}";
        String cause = java.util.UUID.randomUUID().toString();
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("Temporary identity store outage"))
                .doNothing().when(actors).checkPublication(WorkspaceFetchJournalTest.GRANT, ACTOR,
                        RepositoryScope.CENTRAL_WRITE, false);

        var submitted = mvc.perform(post(API + "/fetch").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "retryable-fetch").header("X-Causation-Id", cause).content(request))
                .andExpect(status().isAccepted()).andExpect(header().exists("X-Operation-Id"))
                .andExpect(content().string("")).andReturn();
        String id = submitted.getResponse().getHeader("X-Operation-Id");
        mvc.perform(get(API + "/operations/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.causationId").value(cause))
                .andExpect(jsonPath("$.phase").value("RETRYABLE")).andExpect(jsonPath("$.attempts").value(1));
        mvc.perform(post(API + "/fetch").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "retryable-fetch").header("X-Causation-Id", cause).content(request))
                .andExpect(status().isAccepted()).andExpect(header().string("X-Operation-Id", id))
                .andExpect(content().string(""));
        assertThat(tracking.head(target)).isEqualTo(base);
        mvc.perform(delete(API + "/operations/{id}", id)).andExpect(status().isConflict());

        mvc.perform(post(API + "/operations/{id}/retry", id)).andExpect(status().isOk())
                .andExpect(header().string("X-Operation-Id", id)).andExpect(jsonPath("$.trackedCommit").value(incoming));
        mvc.perform(get(API + "/operations/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("COMPLETED")).andExpect(jsonPath("$.attempts").value(2));
        mvc.perform(delete(API + "/operations/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("ARCHIVED")).andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.archivedBy").value(WorkspaceFetchJournalTest.ALICE));
        mvc.perform(post(API + "/operations/{id}/retry", id)).andExpect(status().isGone());
        assertThat(tracking.head(target)).isEqualTo(incoming);
        assertThat(target.getHeadCommit("main")).isEqualTo(local);
    }

    @Test
    void explicitForkRoutesUseOnlyThePersistedAuthorityAndRefuseStaleChoices() throws Exception {
        mvc.perform(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$.sourceRepositoryId").value("source"))
                .andExpect(jsonPath("$.trackedCommit").value(base));
        mvc.perform(post(API + "/fetch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCommit\":\"" + incoming + "\",\"expectedTrackedCommit\":\"" + base + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.trackedCommit").value(incoming));
        mvc.perform(post(API + "/fetch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCommit\":\"" + base + "\",\"expectedTrackedCommit\":\"" + base + "\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get(API + "/tag").param("name", "selected")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTagObject").isEmpty());
        mvc.perform(post(API + "/fetch-tag").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"selected\",\"sourceTagObject\":\"" + base + "\",\"trackedCommit\":\"" + incoming + "\"}"))
                .andExpect(status().isConflict());
        assertThat(target.getHeadCommit("main")).isEqualTo(local);
    }
}
