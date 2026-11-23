package com.taxonomy.security;

import com.taxonomy.testsupport.LocalTestLogin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
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
@Tag("ci-app-shard-spring-context-b")
class ApiWriteDenyByDefaultSecurityTest {
    @Autowired private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;
    @Autowired private com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService repositories;
    @Autowired private com.taxonomy.workspace.service.RepositoryWorkspaceService workspaces;

    @Test
    void unauthenticatedRestCallsReturn401EvenWhenAcceptingHtml() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/projects")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void headUsesTheSameScopedReadAuthorityForIntegrationsAndOslc() throws Exception {
        String actor = "http-protocol-user";
        LocalTestLogin.login(applicationContext, actor, "USER");
        var repository = repositories.createRepository("HTTP read methods", actor, "",
                com.taxonomy.workspace.model.RepositoryVisibility.PRIVATE, actor, "draft");
        var workspace = workspaces.createWorkingCopy(actor, repository.getRepositoryId(), "draft", "Protocol", "");
        var context = com.taxonomy.workspace.service.RepositoryContext.workspace(
                repository.getRepositoryId(), workspace.getWorkspaceId(), workspace.getCurrentBranch(), actor);
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
    void architectReachesSelfServiceWorkspaceProvisioning() throws Exception {
        LocalTestLogin.login(applicationContext, "http-architect-provision", "ARCHITECT");
        mockMvc.perform(post("/api/workspace/provision").with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("architect workspace provisioning must pass authorization")
                        .isNotEqualTo(403));
    }

    @Test
    void userReachesSelfServiceWorkspaceProvisioning() throws Exception {
        LocalTestLogin.login(applicationContext, "http-user-provision", "USER");
        mockMvc.perform(post("/api/workspace/provision").with(csrf()))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("user workspace provisioning must pass authorization")
                        .isNotEqualTo(403));
    }

    @Test
    void userReachesEditorWorkingCopySourceValidationWithCsrf() throws Exception {
        LocalTestLogin.login(applicationContext, "http-editor-source-validation", "USER");
        // Preserve the former repository-workspaces route's audience. An invalid
        // source reaches the controller's exact-context check without creating data.
        mockMvc.perform(post("/api/architecture/editor/workspaces").with(csrf())
                        .param("workspaceId", "")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void editorWorkingCopyCreationRejectsInvalidCsrf() throws Exception {
        // TestSecuritySupport adds a valid default token; explicitly replace it.
        mockMvc.perform(post("/api/architecture/editor/workspaces").with(csrf().useInvalidToken())
                        .param("workspaceId", "")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
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
