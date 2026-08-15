package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real SecurityFilterChain and ownership evidence for workspace/repository IDs. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key=",
        "taxonomy.features.multi-repository-api.enabled=false"
})
class WorkspaceAccessSecurityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserWorkspaceRepository workspaceRepository;

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void ownerCanReadOwnWorkspaceMetadata() throws Exception {
        UserWorkspace workspace = workspaceRepository.saveAndFlush(
                workspace("qa-owned-workspace", "alice", false));

        mockMvc.perform(get("/api/workspace/{id}/info", workspace.getWorkspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId()))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void guessedForeignWorkspaceIdIsIndistinguishableFromMissing() throws Exception {
        UserWorkspace foreign = workspaceRepository.saveAndFlush(
                workspace("qa-foreign-workspace", "bob", false));

        mockMvc.perform(get("/api/workspace/{id}/info", foreign.getWorkspaceId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
        mockMvc.perform(get("/api/workspace/{id}/info", "does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void explicitlySharedWorkspaceMetadataRemainsVisible() throws Exception {
        UserWorkspace shared = workspaceRepository.saveAndFlush(
                workspace("qa-shared-workspace", "system", true));

        mockMvc.perform(get("/api/workspace/{id}/info", shared.getWorkspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(shared.getWorkspaceId()));
    }

    @Test
    @WithAnonymousUser
    void anonymousWorkspaceAndRepositoryIdProbesAreUnauthorized() throws Exception {
        mockMvc.perform(get("/api/workspace/guessed/info"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/repositories/guessed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void publicMultiRepositoryApiIsHiddenWhenDefaultOff() throws Exception {
        mockMvc.perform(get("/api/repositories/guessed"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void userRoleCannotCreateRepositoryOrAdministerWorkspace() throws Exception {
        mockMvc.perform(post("/api/repositories")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/workspace/guessed/rename")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"stolen\"}"))
                .andExpect(status().isForbidden());
    }

    private static UserWorkspace workspace(
            String workspaceId,
            String username,
            boolean shared) {
        UserWorkspace workspace = new UserWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setUsername(username);
        workspace.setDisplayName(workspaceId);
        workspace.setCurrentBranch("draft");
        workspace.setBaseBranch("draft");
        workspace.setShared(shared);
        workspace.setArchived(false);
        workspace.setDefault(false);
        workspace.setCreatedAt(Instant.parse("2026-08-15T00:00:00Z"));
        workspace.setLastAccessedAt(Instant.parse("2026-08-15T00:00:00Z"));
        return workspace;
    }
}
