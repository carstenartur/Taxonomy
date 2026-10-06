package com.taxonomy;

import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the graph-semantic search endpoint introduced as part of the
 * Hibernate Search migration.
 *
 * <p>Embeddings are explicitly disabled in this fixture. Unavailable graph
 * searches fail explicitly; zero requested results remain a valid empty result.</p>
 */
@SpringBootTest(properties = "embedding.enabled=false")
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class GraphSearchTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GraphSearchService graphSearchService;

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Test
    void graphSearchDoesNotFabricateCompletedResultsWhenModelIsUnavailable() {
        assertThat(embeddingService.isAvailable()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> graphSearchService.graphSearch(
                "satellite communications", 10, WorkspaceContext.SHARED))
                .isInstanceOf(com.taxonomy.error.SearchUnavailableException.class);
    }

    @Test
    void zeroRequestedResultsRetainAllRequiredGraphFields() {
        GraphSearchResult result = graphSearchService.graphSearch(
                "business process management", 0, WorkspaceContext.SHARED);
        assertThat(result.getMatchedNodes()).isNotNull();
        assertThat(result.getRelationCountByRoot()).isNotNull();
        assertThat(result.getTopRelationTypes()).isNotNull();
        assertThat(result.getSummary()).isNotNull();
    }

    @Test
    void zeroRequestedResultsExplainThatNoSearchWasRequested() {
        GraphSearchResult result = graphSearchService.graphSearch("anything", 0, WorkspaceContext.SHARED);
        assertThat(result.getSummary()).isEqualTo("No graph search results were requested.");
        assertThat(result.getMatchedNodes()).isEmpty();
    }

    @Test
    void negativeRequestedResultsRemainEmptyWithoutInference() {
        GraphSearchResult result = graphSearchService.graphSearch("network services", -1, WorkspaceContext.SHARED);
        assertThat(result.getMatchedNodes()).isEmpty();
        assertThat(result.getRelationCountByRoot()).isEmpty();
    }

    @Test
    void graphSearchEndpointReturnsBadRequestForBlankQuery() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void graphSearchEndpointReportsUnavailableModelForValidQuery() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.matchedNodes").doesNotExist());
    }

    @Test
    void graphSearchEndpointFailsClosedWithDefaultMaxResults() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "satellite")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void graphSearchEndpointRespectsMaxResultsParameter() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "BP").param("maxResults", "5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.matchedNodes").doesNotExist());
    }

    @Test
    void graphSearchEndpointRequiresQueryParam() throws Exception {
        mockMvc.perform(get("/api/search/graph").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
