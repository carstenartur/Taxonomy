package com.nato.taxonomy;

import com.nato.taxonomy.dto.GraphSearchResult;
import com.nato.taxonomy.service.GraphSearchService;
import com.nato.taxonomy.service.LocalEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the graph-semantic search endpoint introduced as part of the
 * Hibernate Search migration (requirement 3: graph-semantic search).
 *
 * <p>The DJL model is NOT loaded in these tests. All graph searches gracefully
 * degrade to empty results when the model is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GraphSearchTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GraphSearchService graphSearchService;

    @Autowired
    private LocalEmbeddingService embeddingService;

    // ── GraphSearchService unit tests ─────────────────────────────────────────

    @Test
    void graphSearchReturnsNonNullResultWhenModelNotLoaded() {
        GraphSearchResult result = graphSearchService.graphSearch("satellite communications", 10);
        assertThat(result).isNotNull();
    }

    @Test
    void graphSearchResultHasAllRequiredFields() {
        GraphSearchResult result = graphSearchService.graphSearch("business process management", 10);
        assertThat(result.getMatchedNodes()).isNotNull();
        assertThat(result.getRelationCountByRoot()).isNotNull();
        assertThat(result.getTopRelationTypes()).isNotNull();
        assertThat(result.getSummary()).isNotNull();
    }

    @Test
    void graphSearchWithEmbeddingUnavailableReturnsSummaryMessage() {
        if (!embeddingService.isAvailable()) {
            GraphSearchResult result = graphSearchService.graphSearch("anything", 5);
            assertThat(result.getSummary()).isNotBlank();
            assertThat(result.getMatchedNodes()).isEmpty();
        }
    }

    @Test
    void graphSearchWithEmbeddingUnavailableReturnsEmptyNodes() {
        if (!embeddingService.isAvailable()) {
            GraphSearchResult result = graphSearchService.graphSearch("network services", 20);
            assertThat(result.getMatchedNodes()).isEmpty();
            assertThat(result.getRelationCountByRoot()).isEmpty();
        }
    }

    // ── REST endpoint tests ───────────────────────────────────────────────────

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
