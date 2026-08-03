package com.taxonomy.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
class ApiWriteDenyByDefaultSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "USER")
    void userCannotReachAnyUnclassifiedApiWriteMethod() throws Exception {
        mockMvc.perform(post("/api/unclassified-write").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/unclassified-write").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/unclassified-write").with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/unclassified-write").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCannotBypassTheUnclassifiedApiWriteDenyRule() throws Exception {
        mockMvc.perform(post("/api/unclassified-write").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userReachesExplicitlyClassifiedReadOnlyPostAnalysis() throws Exception {
        mockMvc.perform(post("/api/recommend")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "scores": {},
                                  "businessText": "Provide traceable secure communication.",
                                  "minScore": 50
                                }
                                """))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("explicitly classified analysis POST must pass authorization")
                        .isNotEqualTo(403));
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotTriggerDerivedMetadataWrites() throws Exception {
        mockMvc.perform(post("/api/architecture/metadata/recompute").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void userCannotPersistLegacyCoverageMappings() throws Exception {
        mockMvc.perform(post("/api/coverage/record")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requirementId": "REQ-SECURITY",
                                  "requirementText": "security contract",
                                  "scores": {"CP-1000": 80},
                                  "minScore": 50
                                }
                                """))
                .andExpect(status().isForbidden());
    }
}
