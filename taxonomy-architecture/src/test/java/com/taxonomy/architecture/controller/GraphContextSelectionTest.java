package com.taxonomy.architecture.controller;

import com.taxonomy.architecture.service.*;
import com.taxonomy.workspace.service.*;
import com.taxonomy.dto.GraphNeighborhoodView;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphContextSelectionTest {
    @Test void requiresExplicitSelectionAndRejectsMismatchBeforeTraversal() throws Exception {
        var graph = mock(ArchitectureGraphQueryService.class);
        var resolver = mock(WorkspaceResolver.class);
        var context = RepositoryContext.centralRead("repo", "main", "alice");
        doReturn(context).when(resolver).resolveCurrentRepositoryContextForRead();
        var mvc = MockMvcBuilders.standaloneSetup(new GraphQueryApiController(graph,
                mock(EnrichedImpactService.class), mock(ApqcHierarchyService.class), resolver)).build();
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
                .andExpect(status().isOk());
        verify(graph).findDownstream(context,"a".repeat(40),"A",2);
    }
}
