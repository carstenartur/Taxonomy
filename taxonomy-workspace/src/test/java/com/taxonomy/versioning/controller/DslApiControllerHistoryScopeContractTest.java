package com.taxonomy.versioning.controller;

import com.taxonomy.dto.ElementHistoryAggregation;
import com.taxonomy.versioning.model.ArchitectureCommitIndex;
import com.taxonomy.versioning.service.CommitIndexService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Retained history HTTP routes must preserve the selected repository without provisioning a new one. */
class DslApiControllerHistoryScopeContractTest {
    private static final String RELATION = "BP RELATED_TO CP";
    private final RepositoryContext selected = RepositoryContext.workspace(
            "selected-repository", "alice-workspace", "review", "alice");
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final CommitIndexService history = mock(CommitIndexService.class);
    private final WorkspaceDslReadPort git = mock(WorkspaceDslReadPort.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new CommitHistoryQueryController(history, resolver, git,
                JsonMapper.builder().build())).build();
        when(resolver.resolveUnambiguousRepositoryForRead()).thenReturn(selected);
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

        var order = inOrder(resolver, history);
        order.verify(resolver).resolveUnambiguousRepositoryForRead();
        order.verify(history).aggregateElementHistory(eq("BP"), same(selected));
        order.verify(resolver).resolveUnambiguousRepositoryForRead();
        order.verify(history).findByRelation(eq(RELATION), same(selected));
        order.verifyNoMoreInteractions();
        verifyNoInteractions(git);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/dsl/history/element/BP/aggregation", "/api/dsl/history/relation",
            "/api/dsl/history/element/BP", "/api/dsl/history/search", "/api/dsl/history/search-versioned"})
    void ambiguousReadStopsBeforeAnyProjectionOrFallback(String path) throws Exception {
        when(resolver.resolveUnambiguousRepositoryForRead()).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        mvc.perform(get(path).param("key", RELATION).param("query", "BP")).andExpect(status().isConflict());
        verifyNoInteractions(history, git);
        verify(resolver, never()).resolveCurrentRepositoryContext();
    }
}
