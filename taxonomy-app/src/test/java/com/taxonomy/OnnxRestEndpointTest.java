package com.taxonomy;

import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST endpoint tests for embedding and semantic-search APIs with a real pinned
 * ONNX model. Ordinary and database-focused Maven suites exclude the
 * {@code onnx} group; the {@code ci} and {@code onnx} profiles execute it
 * deliberately with an explicitly provisioned model directory.
 */
@Tag("onnx")
@SpringBootTest(properties = {
        "embedding.enabled=true",
        "embedding.allow-download=false"
})
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class OnnxRestEndpointTest {

    private static final Duration SEMANTIC_READY_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(250);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Autowired
    private LocalOnnxIndexInitializer indexInitializer;

    @BeforeEach
    void awaitSemanticIndexReadiness() throws Exception {
        long deadline = System.nanoTime() + SEMANTIC_READY_TIMEOUT.toNanos();
        while (!indexInitializer.isNodeSearchReady()) {
            if (indexInitializer.getState() == LocalOnnxIndexInitializer.State.FAILED) {
                throw new AssertionError("LOCAL_ONNX semantic index failed before REST verification: "
                        + indexInitializer.getDetail()
                        + "; indexedNodes=" + embeddingService.indexedNodeCount());
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("LOCAL_ONNX semantic index did not become ready within "
                        + SEMANTIC_READY_TIMEOUT
                        + "; state=" + indexInitializer.getState()
                        + "; detail=" + indexInitializer.getDetail()
                        + "; indexedNodes=" + embeddingService.indexedNodeCount());
            }
            Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
        }
    }

    @Test
    void embeddingStatusEndpointIsOk() throws Exception {
        mockMvc.perform(get("/api/embedding/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.modelAvailable").value(true))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.semanticReady").value(true))
                .andExpect(jsonPath("$.indexedNodes").value(
                        org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.indexedNodesAtReadiness").value(
                        org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void semanticSearchReturnsArray() throws Exception {
        mockMvc.perform(get("/api/search/semantic")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void hybridSearchReturnsResults() throws Exception {
        mockMvc.perform(get("/api/search/hybrid")
                        .param("q", "satellite communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void findSimilarReturnsArray() throws Exception {
        // CO is one of the 8 taxonomy roots (BP, BR, CP, CI, CO, CR, IP, UA)
        mockMvc.perform(get("/api/search/similar/CO")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void graphSearchReturnsStructuredResult() throws Exception {
        mockMvc.perform(get("/api/search/graph")
                        .param("q", "communications")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedNodes").isArray())
                .andExpect(jsonPath("$.summary").isString());
    }
}
