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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link RateLimitFilter} and
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
     * Runtime preferences deliberately override static properties in production.
     * Replace the persistent service here so this test remains independent of
     * preferences committed by another Spring context in the full reactor.
     */
    @MockitoBean
    private PreferencesService preferencesService;

    @BeforeEach
    void resetCounters() {
        // Preserve normal fallback behaviour for unrelated preference lookups.
        when(preferencesService.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(preferencesService.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(preferencesService.getBoolean(anyString(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        // Pin the runtime preference that has precedence over the test property.
        when(preferencesService.getInt("rate-limit.per-minute", 3)).thenReturn(3);

        // Clear per-principal/peer counters between tests.
        rateLimitFilter.clearCounters();
    }

    @Test
    void rateLimitFilterAllowsRequestsUnderLimit() throws Exception {
        for (int index = 0; index < 3; index++) {
            mockMvc.perform(get("/api/analyze-node")
                            .param("parentCode", "BP")
                            .param("businessText", "test requirement")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rateLimitFilterBlocks429AfterLimitExceeded() throws Exception {
        for (int index = 0; index < 3; index++) {
            mockMvc.perform(get("/api/analyze-node")
                            .param("parentCode", "BP")
                            .param("businessText", "test requirement")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/analyze-node")
                        .param("parentCode", "BP")
                        .param("businessText", "test requirement")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(429));
    }

    @Test
    void rateLimitFilter429ResponseContainsJsonError() throws Exception {
        for (int index = 0; index < 3; index++) {
            mockMvc.perform(get("/api/analyze-node")
                            .param("parentCode", "BP")
                            .param("businessText", "test")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
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
        for (int index = 0; index < 5; index++) {
            mockMvc.perform(get("/api/taxonomy").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void rateLimitFilterBeanIsRegistered() {
        assertThat(rateLimitFilter).isNotNull();
    }

    @Test
    void illegalArgumentExceptionReturnsBadRequestJson() throws Exception {
        mockMvc.perform(get("/api/search")
                        .param("q", "")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzeEndpointReturnsBadRequestForEmptyText() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
