package com.taxonomy.versioning.controller;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dto.ElementHistoryAggregation;
import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.versioning.service.ConflictDetectionService;
import com.taxonomy.versioning.service.DslOperationsFacade;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryStateGuard;
import com.taxonomy.workspace.service.WorkspaceArchitectureVersionPort;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Retained history HTTP routes must preserve the selected repository through the real workspace facade. */
class DslApiControllerHistoryScopeContractTest {
    private static final String RELATION = "BP RELATED_TO CP";
    private final RepositoryContext selected = RepositoryContext.workspace(
            "selected-repository", "alice-workspace", "review", "alice");
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final RepositoryStateService state = mock(RepositoryStateService.class);
    private final CommitIndexService history = mock(CommitIndexService.class);
    private final DslGitRepositoryFactory repositories = mock(DslGitRepositoryFactory.class);
    private final DslReadWorkspaceContextResolver readContext = mock(DslReadWorkspaceContextResolver.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var facade = new DslOperationsFacade(repositories, history, mock(ConflictDetectionService.class),
                mock(RepositoryStateGuard.class), state, resolver, mock(WorkspaceArchitectureVersionPort.class));
        mvc = MockMvcBuilders.standaloneSetup(new DslApiController(facade, resolver, readContext)).build();
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(selected);
    }

    @Test
    void aggregationAndRelationHistoryReturnSelectedWorkspaceProjection() throws Exception {
        var aggregation = new ElementHistoryAggregation("BP", Instant.EPOCH,
                Instant.parse("2026-01-02T00:00:00Z"), 2, 0.5, List.of("Review update", "Initial version"));
        when(history.aggregateElementHistory("BP", selected)).thenReturn(aggregation);
        var commit = new ArchitectureCommitIndex();
        commit.setRepositoryId(selected.repositoryId());
        commit.setWorkspaceId(selected.workspaceId());
        commit.setBranch(selected.branch());
        commit.setCommitId("a".repeat(40));
        commit.setCommitTimestamp(Instant.EPOCH);
        commit.setAffectedRelationIds(RELATION);
        commit.setMessage("Review relation");
        when(history.findByRelation(RELATION, selected)).thenReturn(List.of(commit));

        mvc.perform(get("/api/dsl/history/element/BP/aggregation"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.elementId").value("BP"))
                .andExpect(jsonPath("$.firstSeen").value("1970-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.lastSeen").value("2026-01-02T00:00:00Z"))
                .andExpect(jsonPath("$.occurrenceCount").value(2))
                .andExpect(jsonPath("$.volatility").value(0.5))
                .andExpect(jsonPath("$.recentCommitMessages[0]").value("Review update"))
                .andExpect(jsonPath("$.recentCommitMessages[1]").value("Initial version"));
        mvc.perform(get("/api/dsl/history/relation").param("key", RELATION))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].repositoryId").value(selected.repositoryId()))
                .andExpect(jsonPath("$[0].workspaceId").value(selected.workspaceId()))
                .andExpect(jsonPath("$[0].branch").value("review"))
                .andExpect(jsonPath("$[0].commitId").value("a".repeat(40)))
                .andExpect(jsonPath("$[0].affectedRelationIds").value(RELATION))
                .andExpect(jsonPath("$[0].message").value("Review relation"));

        var order = inOrder(state, resolver, history);
        order.verify(resolver).resolveCurrentUsername();
        order.verify(state).ensureWorkspaceState("alice");
        order.verify(resolver).resolveCurrentRepositoryContext();
        order.verify(history).aggregateElementHistory(eq("BP"), same(selected));
        order.verify(resolver).resolveCurrentUsername();
        order.verify(state).ensureWorkspaceState("alice");
        order.verify(resolver).resolveCurrentRepositoryContext();
        order.verify(history).findByRelation(eq(RELATION), same(selected));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(repositories, readContext);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/dsl/history/element/BP/aggregation", "/api/dsl/history/relation"})
    void missingRepositoryContextFailsBeforeAnyProjectionOrFallback(String path) {
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(null);
        assertThatThrownBy(() -> mvc.perform(get(path).param("key", RELATION)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Repository context resolver returned null");
        verify(state).ensureWorkspaceState("alice");
        verifyNoInteractions(history, repositories, readContext);
        verify(resolver, never()).resolveCurrentContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/dsl/history/element/BP/aggregation", "/api/dsl/history/relation"})
    void failedProvisioningStopsBeforeContextOrProjectionRead(String path) {
        doThrow(new IllegalStateException("workspace unavailable")).when(state).ensureWorkspaceState("alice");
        assertThatThrownBy(() -> mvc.perform(get(path).param("key", RELATION)))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("workspace unavailable");
        verify(resolver, never()).resolveCurrentRepositoryContext();
        verify(resolver, never()).resolveCurrentContext();
        verifyNoInteractions(history, repositories, readContext);
    }
}
