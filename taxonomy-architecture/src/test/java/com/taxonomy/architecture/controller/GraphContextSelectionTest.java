package com.taxonomy.architecture.controller;

import com.taxonomy.workspace.controller.ExplicitRepositoryReadSelection;

import com.taxonomy.architecture.service.*;
import com.taxonomy.workspace.service.*;
import com.taxonomy.dto.GraphNeighborhoodView;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class GraphContextSelectionTest {
    @Test void requiresExplicitSelectionAndRejectsMismatchBeforeTraversal() throws Exception {
        var graph = mock(ArchitectureGraphQueryService.class);
        var resolver = mock(WorkspaceResolver.class);
        var context = RepositoryContext.centralRead("repo", "main", "alice");
        doReturn(context).when(resolver).resolveCurrentRepositoryContextForRead();
        var mvc = MockMvcBuilders.standaloneSetup(new GraphQueryApiController(graph,
                mock(EnrichedImpactService.class), mock(ApqcHierarchyService.class), new ExplicitRepositoryReadSelection(resolver)))
                .setControllerAdvice(new ObligationApiErrors()).build();
        mvc.perform(get("/api/graph/node/A/downstream")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/graph/node/A/downstream").param("workspaceId", "")
                .param("repositoryId", "other").param("branch", "main").param("commitId", "a".repeat(40)))
                .andExpect(status().isConflict());
        verifyNoInteractions(graph);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenThrow(
                new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/graph/node/A/downstream").param("workspaceId", "")
                .param("repositoryId", "private").param("branch", "main").param("commitId", "a".repeat(40)))
                .andExpect(status().isNotFound());
        verifyNoInteractions(graph);
        doReturn(context).when(resolver).resolveCurrentRepositoryContextForRead();
        when(graph.findDownstream(context,"a".repeat(40),"A",2)).thenReturn(new GraphNeighborhoodView());
        mvc.perform(get("/api/graph/node/A/downstream").param("workspaceId", "")
                .param("repositoryId", "repo").param("branch", "main").param("commitId", "a".repeat(40)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Taxonomy-Repository", "repo"))
                .andExpect(header().string("X-Taxonomy-Workspace-Id", ""))
                .andExpect(header().string("X-Taxonomy-Branch", "main"))
                .andExpect(header().string("X-Taxonomy-Commit", "a".repeat(40)));
        verify(graph).findDownstream(context,"a".repeat(40),"A",2);
        mvc.perform(get("/api/graph/node/A/downstream").header("X-Taxonomy-Workspace-Id", "")
                .param("repositoryId", "repo").param("branch", "main").param("commitId", "A".repeat(40)))
                .andExpect(status().isOk());
        verify(graph, times(2)).findDownstream(context,"a".repeat(40),"A",2);
        when(graph.findDownstream(context,"a".repeat(40),"A",2))
                .thenThrow(new IllegalStateException("Selected graph head is no longer proven"));
        mvc.perform(get("/api/graph/node/A/downstream").param("workspaceId", "")
                .param("repositoryId", "repo").param("branch", "main").param("commitId", "a".repeat(40)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(header().string("X-Taxonomy-Source-State", "FAILED"));
    }

    @Test
    void aMovedOrDeletedGraphHeadIsDistinguishedFromAnUnreadableStore() throws Exception {
        String original = "a".repeat(40);
        String advanced = "b".repeat(40);
        var context = RepositoryContext.centralRead("repo", "draft", "alice");
        var resolver = mock(WorkspaceResolver.class);
        when(resolver.resolveCurrentRepositoryContextForRead()).thenReturn(context);
        var graph = mock(ArchitectureGraphQueryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GraphQueryApiController(graph, mock(EnrichedImpactService.class),
                mock(ApqcHierarchyService.class), new ExplicitRepositoryReadSelection(resolver)))
                .setControllerAdvice(new ObligationApiErrors()).build();
        when(graph.findDownstream(context, original, "A", 2)).thenThrow(
                new IllegalStateException("verification", new BranchHeadConflictException("draft", original, advanced, "changed")),
                new IllegalStateException("verification", new BranchHeadConflictException("draft", original, null, "deleted")),
                new IllegalStateException("storage", new IOException("backend detail")));
        var request = get("/api/graph/node/A/downstream").param("workspaceId", "").param("repositoryId", "repo")
                .param("branch", "draft").param("commitId", original);
        mvc.perform(request).andExpect(status().isConflict()).andExpect(header().string("X-Taxonomy-Source-State", "STALE"))
                .andExpect(header().string("X-Taxonomy-Commit", advanced));
        mvc.perform(request).andExpect(status().isConflict()).andExpect(header().string("X-Taxonomy-Source-State", "MISSING"))
                .andExpect(header().doesNotExist("X-Taxonomy-Commit"));
        mvc.perform(request).andExpect(status().isServiceUnavailable()).andExpect(header().string("X-Taxonomy-Source-State", "FAILED"))
                .andExpect(header().doesNotExist("X-Taxonomy-Commit"));
    }
}
