package com.taxonomy;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.taxonomy.shared.service.LocalEmbeddingService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Stufe 3: REST endpoints via MockMvc — with real ONNX model.
 * <p>
 * Proves that the REST API endpoints ({@code /api/embedding/status},
 * {@code /api/search/semantic}, {@code /api/search/hybrid}, {@code /api/search/similar},
 * {@code /api/search/graph}) deliver real results when the model is loaded.
 * <p>
 * Opt-in: only runs when the {@code runOnnxTests} system property is set.
 * Run with: {@code mvn test -DrunOnnxTests -Dtest=OnnxRestEndpointTest}
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runOnnxTests", matches = ".*")
class OnnxRestEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalEmbeddingService embeddingService;

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
    void semanticSearchReturnsNonEmptyResults() throws Exception {
        mockMvc.perform(get("/api/search/semantic")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ── Test 3.3 ─────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void hybridSearchReturnsNonEmptyResults() throws Exception {
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
    void findSimilarReturnsRelatedNodes() throws Exception {
        mockMvc.perform(get("/api/search/similar/CO")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ── Test 3.5 ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void semanticSearchResultsContainExpectedFields() throws Exception {
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
