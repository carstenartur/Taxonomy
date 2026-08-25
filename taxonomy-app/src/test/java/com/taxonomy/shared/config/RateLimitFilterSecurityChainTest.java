package com.taxonomy.shared.config;

import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves that the registered servlet filter observes the authenticated principal. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "llm.mock=true",
    "taxonomy.rate-limit.per-minute=1"
})
class RateLimitFilterSecurityChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @MockitoBean
    private PreferencesService preferencesService;

    @BeforeEach
    void configureLimit() {
        when(preferencesService.getInt(
                eq("rate-limit.per-minute"), anyInt())).thenReturn(1);
        rateLimitFilter.clearCounters();
    }

    @Test
    void forwardingHeaderCannotResetAuthenticatedPrincipalBudget() throws Exception {
        analyzeNode("alice", "203.0.113.10")
                .andExpect(status().isOk());
        analyzeNode("alice", "203.0.113.99")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void authenticatedPrincipalsBehindOnePeerHaveIndependentBudgets() throws Exception {
        analyzeNode("alice", "203.0.113.10")
                .andExpect(status().isOk());
        analyzeNode("bob", "203.0.113.10")
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions analyzeNode(
            String username,
            String forwardedFor) throws Exception {
        return mockMvc.perform(get("/api/analyze-node")
                .with(user(username).roles("ADMIN"))
                .header("X-Forwarded-For", forwardedFor)
                .param("parentCode", "BP")
                .param("businessText", "resilient communications"));
    }
}
