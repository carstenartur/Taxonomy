package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the real servlet-filter and Spring-Security interaction for monitoring clients. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "admin.token=metrics-admin-token-1234567890",
        "taxonomy.admin-password=local-admin-password-1234567890",
        "taxonomy.security.require-password-change=false",
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
class ActuatorAdminTokenSecurityTests {

    private static final String ADMIN_TOKEN = "metrics-admin-token-1234567890";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void configuredBearerTokenCanReadPrometheusWithoutInteractiveLogin()
            throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void configuredLegacyHeaderCanReadPrometheusWithoutInteractiveLogin()
            throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void configuredBearerTokenWorksBehindAContextPath() throws Exception {
        mockMvc.perform(get("/taxonomy/actuator/prometheus")
                        .contextPath("/taxonomy")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void missingTokenCannotReadPrometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contextPathCannotBypassMissingTokenProtection() throws Exception {
        mockMvc.perform(get("/taxonomy/actuator/prometheus")
                        .contextPath("/taxonomy"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void incorrectBearerTokenCannotReadPrometheus() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenAuthenticationDoesNotDisableCsrfForWritingRequests() throws Exception {
        mockMvc.perform(post("/actuator/metrics")
                        .with(csrf().useInvalidToken())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthProbeRemainsPublicWhenAdminTokenIsConfigured() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }
}
