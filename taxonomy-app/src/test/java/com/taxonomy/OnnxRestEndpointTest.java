package com.taxonomy;

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
 * REST endpoint tests for the embedding / semantic search APIs.
 * <p>
 * Uses {@link LocalEmbeddingService} as a Spring bean — the service
 * downloads the model lazily on first use (same production codepath).
 * No CI-cache assumptions, no manual model setup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class OnnxRestEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LocalEmbeddingService embeddingService;

    @Test
    void embeddingStatusEndpointIsOk() throws Exception {
        // trigger lazy model load so the status reports available=true
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
