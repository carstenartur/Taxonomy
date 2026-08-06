package com.taxonomy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Test
    void embeddingStatusEndpointIsOk() throws Exception {
        // trigger model load so the status reports available=true
        embeddingService.embed("warm-up");

        mockMvc.perform(get("/api/embedding/status").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.indexedNodes").isNumber());
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
