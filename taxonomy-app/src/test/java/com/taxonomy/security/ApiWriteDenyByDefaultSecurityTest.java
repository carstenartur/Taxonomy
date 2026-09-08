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
    @Autowired private com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService repositories;
    @Autowired private com.taxonomy.workspace.service.RepositoryWorkspaceService workspaces;

    @Test
    @WithMockUser(roles = "USER")
    void headUsesTheSameScopedReadAuthorityForIntegrationsAndOslc() throws Exception {
        var repository = repositories.createRepository("HTTP read methods", "user", "", com.taxonomy.workspace.model.RepositoryVisibility.PRIVATE, "user", "draft");
        var workspace = workspaces.createWorkingCopy("user", repository.getRepositoryId(), "draft", "Protocol", "");
        var context = com.taxonomy.workspace.service.RepositoryContext.workspace(repository.getRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), "user");
        for (String path : java.util.List.of("/api/integrations/profiles", "/oslc/scopes/" + com.taxonomy.editor.persistence.EditorJournal.scope(context) + "/catalog"))
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(path)
                            .param("repositoryId", context.repositoryId()).param("workspaceId", context.workspaceId()).param("branch", context.branch()))
                    .andExpect(status().isOk());
    }

    @Test
    void integrationOptionsRetainsTheExistingPublicApiPreflightPolicy() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/integrations/profiles"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void integrationAndOslcUnknownWritesRemainDeniedEvenForAdministrators() throws Exception {
        for (String path : java.util.List.of("/api/integrations/unclassified", "/oslc/unclassified")) {
            mockMvc.perform(put(path).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(patch(path).with(csrf())).andExpect(status().isForbidden());
            mockMvc.perform(delete(path).with(csrf())).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/oslc/unclassified").with(csrf())).andExpect(status().isForbidden());
    }

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
    @WithMockUser(roles = "ARCHITECT")
    void architectReachesSelfServiceWorkspaceProvisioning() throws Exception {
        mockMvc.perform(post("/api/workspace/provision").with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("architect workspace provisioning must pass authorization")
                        .isNotEqualTo(403));
    }

    @Test
    @WithMockUser(roles = "USER")
    void userReachesSelfServiceWorkspaceProvisioning() throws Exception {
        mockMvc.perform(post("/api/workspace/provision").with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("user workspace provisioning must pass authorization")
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
