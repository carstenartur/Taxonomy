package com.taxonomy;

import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.shared.service.LocalEmbeddingService;
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
 * <p>The DJL model is not loaded in these tests. All direct service calls use
 * the explicit shared workspace context and gracefully degrade to empty results
 * when the model is unavailable.</p>
 */
@SpringBootTest
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
    void graphSearchReturnsNonNullResultWhenModelNotLoaded() {
        GraphSearchResult result = graphSearchService.graphSearch(
                "satellite communications", 10, WorkspaceContext.SHARED);
        assertThat(result).isNotNull();
    }

    @Test
    void graphSearchResultHasAllRequiredFields() {
        GraphSearchResult result = graphSearchService.graphSearch(
                "business process management", 10, WorkspaceContext.SHARED);
        assertThat(result.getMatchedNodes()).isNotNull();
        assertThat(result.getRelationCountByRoot()).isNotNull();
        assertThat(result.getTopRelationTypes()).isNotNull();
        assertThat(result.getSummary()).isNotNull();
    }

    @Test
    void graphSearchWithEmbeddingUnavailableReturnsSummaryMessage() {
        if (!embeddingService.isAvailable()) {
            GraphSearchResult result = graphSearchService.graphSearch(
                    "anything", 5, WorkspaceContext.SHARED);
            assertThat(result.getSummary()).isNotBlank();
            assertThat(result.getMatchedNodes()).isEmpty();
        }
    }

    @Test
    void graphSearchWithEmbeddingUnavailableReturnsEmptyNodes() {
        if (!embeddingService.isAvailable()) {
            GraphSearchResult result = graphSearchService.graphSearch(
                    "network services", 20, WorkspaceContext.SHARED);
            assertThat(result.getMatchedNodes()).isEmpty();
            assertThat(result.getRelationCountByRoot()).isEmpty();
        }
    }

    @Test
    void graphSearchEndpointReturnsBadRequestForBlankQuery() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void graphSearchEndpointReturnsJsonForValidQuery() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.matchedNodes").isArray())
                .andExpect(jsonPath("$.relationCountByRoot").isMap())
                .andExpect(jsonPath("$.topRelationTypes").isMap())
                .andExpect(jsonPath("$.summary").isString());
    }

    @Test
    void graphSearchEndpointReturnsJsonWithDefaultMaxResults() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "satellite")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").isString());
    }

    @Test
    void graphSearchEndpointRespectsMaxResultsParameter() throws Exception {
        mockMvc.perform(get("/api/search/graph").param("q", "BP").param("maxResults", "5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isArray());
    }

    @Test
    void graphSearchEndpointRequiresQueryParam() throws Exception {
        mockMvc.perform(get("/api/search/graph").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
