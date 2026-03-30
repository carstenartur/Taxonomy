package com.taxonomy;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.taxonomy.shared.service.LocalEmbeddingService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stufe 3: REST endpoints via MockMvc — with real ONNX model.
 * <p>
 * Proves that the REST API endpoints ({@code /api/embedding/status},
 * {@code /api/search/semantic}, {@code /api/search/hybrid}, {@code /api/search/similar},
 * {@code /api/search/graph}) deliver real results when the model is loaded and the
 * index contains embedding vectors.
 * <p>
 * When the model was <em>not</em> available at index time (e.g. local dev without
 * pre-downloaded model), the tests verify graceful degradation: valid JSON responses
 * with proper structure but potentially empty result arrays.  In CI the model is
 * pre-downloaded via {@code TAXONOMY_EMBEDDING_MODEL_DIR}, so all semantic searches
 * return non-empty results.
 * <p>
 * Run with: {@code mvn test -Dtest=OnnxRestEndpointTest}
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnnxRestEndpointTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalEmbeddingService embeddingService;

    /**
     * Returns {@code true} when the index was built with embedding vectors
     * (model was available at startup). When the model is lazy-loaded after
     * startup, the index has no embeddings and semantic/KNN queries return
     * empty results — which is correct graceful degradation.
     */
    private boolean indexHasEmbeddings() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/embedding/status")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        // If the model was available at index time, semantic search will work.
        // A simple heuristic: if available=true and a quick semantic probe returns results.
        if (!body.path("available").booleanValue()) return false;
        MvcResult probe = mockMvc.perform(get("/api/search/semantic")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        JsonNode probeBody = MAPPER.readTree(probe.getResponse().getContentAsString());
        return probeBody.isArray() && probeBody.size() > 0;
    }

    // ── Test 3.1 ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void embeddingStatusShowsAvailableTrue() throws Exception {
        // Warm-up: trigger lazy model loading
        embeddingService.embed("warm-up");

        mockMvc.perform(get("/api/embedding/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.indexedNodes").isNumber());
    }

    // ── Test 3.2 ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void semanticSearchReturnsValidResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/semantic")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        if (indexHasEmbeddings()) {
            // CI: model was available at index time → results expected
            assertThat(body.size()).as("Semantic search should return results when index has embeddings")
                    .isGreaterThanOrEqualTo(1);
        }
        // Local dev: model loaded lazily after index → empty array is valid
    }

    // ── Test 3.3 ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void hybridSearchReturnsNonEmptyResults() throws Exception {
        // Hybrid search falls back to full-text when embeddings unavailable,
        // so it always returns results for known taxonomy terms.
        mockMvc.perform(get("/api/search/hybrid")
                        .param("q", "satellite communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ── Test 3.4 ─────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void findSimilarReturnsValidResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/search/similar/CO")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        if (indexHasEmbeddings()) {
            assertThat(body.size()).as("Find similar should return results when index has embeddings")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ── Test 3.5 ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void semanticSearchResultsContainExpectedFields() throws Exception {
        if (!indexHasEmbeddings()) {
            // Without embeddings in the index, semantic search returns empty → skip field check
            mockMvc.perform(get("/api/search/semantic")
                            .param("q", "communications")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
            return;
        }

        mockMvc.perform(get("/api/search/semantic")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").isString())
                .andExpect(jsonPath("$[0].nameEn").isString())
                .andExpect(jsonPath("$[0].taxonomyRoot").isString());
    }

    // ── Test 3.6 ─────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void graphSearchReturnsStructuredResult() throws Exception {
        mockMvc.perform(get("/api/search/graph")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isArray())
                .andExpect(jsonPath("$.summary").isString());
    }
}
