package com.taxonomy.security.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves that an empty machine-token configuration preserves ordinary user authorization. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "admin.token=",
        "taxonomy.admin-password=local-admin-password-1234567890",
        "taxonomy.security.require-password-change=false",
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
class ActuatorWithoutAdminTokenSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sensitiveEndpointRetainsAuthenticatedUserBoundary() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk());
    }

    @Test
    void exactDiscoveryRootRetainsAuthenticatedUserBoundaryWithContextPath()
            throws Exception {
        mockMvc.perform(get("/taxonomy/actuator")
                        .contextPath("/taxonomy"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/taxonomy/actuator")
                        .contextPath("/taxonomy")
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk());
    }
}
