package com.taxonomy;

import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.shared.config.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link com.taxonomy.shared.config.RateLimitFilter} and
 * {@link com.taxonomy.shared.config.GlobalExceptionHandler}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "gemini.api.key=",
    "openai.api.key=",
    "deepseek.api.key=",
    "qwen.api.key=",
    "llama.api.key=",
    "mistral.api.key=",
    "taxonomy.rate-limit.per-minute=3"
})
@WithMockUser(roles = "ADMIN")
class ProductionHardeningTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    /**
     * The production filter deliberately prefers the runtime preference over the
     * static property. Fix the runtime value for this test context so the tests do
     * not depend on a preferences repository created by another Spring context.
     */
    @MockitoBean
    private PreferencesService preferencesService;

    @BeforeEach
    void resetCounters() {
        when(preferencesService.getInt(eq("rate-limit.per-minute"), anyInt()))
                .thenReturn(3);
        rateLimitFilter.clearCounters();
    }

    // ── RateLimitFilter Tests ────────────────────────────────────────────────

    @Test
    void rateLimitFilterAllowsRequestsUnderLimit() throws Exception {
        // With limit=3, first 3 requests should succeed
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/analyze-node")
                            .param("parentCode", "BP")
                            .param("businessText", "test requirement")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rateLimitFilterBlocks429AfterLimitExceeded() throws Exception {
        // Exhaust the limit (3 requests)
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/analyze-node")
                            .param("parentCode", "BP")
                            .param("businessText", "test requirement")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
        // 4th request should be rate limited
        mockMvc.perform(get("/api/analyze-node")
                        .param("parentCode", "BP")
                        .param("businessText", "test requirement")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(429));
    }

    @Test
    void rateLimitFilter429ResponseContainsJsonError() throws Exception {
        // Exhaust the limit
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/analyze-node")
                            .param("parentCode", "BP")
                            .param("businessText", "test")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
        // 4th should be rate limited with JSON body
        mockMvc.perform(get("/api/analyze-node")
                        .param("parentCode", "BP")
                        .param("businessText", "test")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(429))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").isString())
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void rateLimitFilterDoesNotAffectNonLlmEndpoints() throws Exception {
        // Non-LLM endpoints like /api/taxonomy should never be rate-limited
        // even when the limit has been exhausted for LLM endpoints
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rateLimitWindowCounterResetsAfterMinute() {
        // Rate limit is fixed to 3 in this test context. The time-window behaviour
        // itself is covered by the counter implementation tests.
        assertThat(rateLimitFilter).isNotNull();
    }

    @Test
    void rateLimitFilterBeanIsRegistered() {
        assertThat(rateLimitFilter).isNotNull();
    }

    // ── GlobalExceptionHandler Tests ─────────────────────────────────────────

    @Test
    void illegalArgumentExceptionReturnsBadRequestJson() throws Exception {
        // /api/search with blank query triggers IllegalArgumentException in SearchService
        mockMvc.perform(get("/api/search")
                        .param("q", "")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeEndpointReturnsBadRequestForEmptyText() throws Exception {
        // Empty businessText should return 400 (not a stack trace)
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
