package com.taxonomy.workspace.controller;

import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
class WorkspaceScopedEndpointIsolationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkspaceResolver workspaceResolver;

    @MockitoBean
    private RepositoryStateService repositoryStateService;

    @BeforeEach
    void failWorkspaceProvisioning() {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        doThrow(new IllegalStateException("workspace database unavailable"))
                .when(repositoryStateService).ensureWorkspaceState("architect");
    }

    @Test
    @WithMockUser(username = "architect", roles = "USER")
    void analysisStopsBeforeControllerCanFallBackToShared() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessText": "A workspace-scoped requirement",
                                  "includeArchitectureView": true
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(workspaceResolver, never()).resolveCurrentContext();
    }

    @Test
    @WithMockUser(username = "architect", roles = "USER")
    void graphSearchStopsBeforeControllerCanReadSharedRelations() throws Exception {
        mockMvc.perform(get("/api/search/graph")
                        .queryParam("q", "secure communication"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(workspaceResolver, never()).resolveCurrentContext();
    }
}
