package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.config.RepositoryTopologyProperties;
import com.taxonomy.workspace.service.ExternalSyncService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExternalSyncController.class)
@Import(com.taxonomy.security.config.SecurityConfig.class)
class ExternalSyncControllerTest {

    private static final String BASE = "/api/workspace";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExternalSyncService externalSyncService;

    @MockitoBean
    private WorkspaceResolver workspaceResolver;

    @MockitoBean
    private RepositoryTopologyProperties topologyProperties;

    @Test
    @WithMockUser(username = "architect", roles = "ARCHITECT")
    void statusIsAvailableToArchitect() throws Exception {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        when(externalSyncService.getStatus("architect"))
                .thenReturn(new ExternalSyncService.SyncStatus(
                        "INTERNAL_SHARED", false, false, false, null));

        mockMvc.perform(get(BASE + "/external-sync/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topologyMode").value("INTERNAL_SHARED"));
    }

    @Test
    @WithMockUser(username = "architect", roles = "ARCHITECT")
    void pullIsAvailableToArchitect() throws Exception {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        when(externalSyncService.pull("architect", null))
                .thenReturn(new ExternalSyncService.SyncResult(
                        true, "pulled", null, null, false));

        mockMvc.perform(post(BASE + "/external-sync/pull").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "architect", roles = "ARCHITECT")
    void pushIsAvailableToArchitect() throws Exception {
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("architect");
        when(externalSyncService.push("architect", null))
                .thenReturn(new ExternalSyncService.SyncResult(
                        true, "pushed", null, null, false));

        mockMvc.perform(post(BASE + "/external-sync/push").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void configureIsAvailableToAdmin() throws Exception {
        when(externalSyncService.configure(
                anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ExternalSyncService.ConfigurationResult(
                        true,
                        "configured",
                        "EXTERNAL_CANONICAL",
                        "https://example.com/repo.git"));

        mockMvc.perform(put(BASE + "/configure")
                        .with(csrf())
                        .param("topologyMode", "EXTERNAL_CANONICAL")
                        .param("externalUrl", "https://example.com/repo.git"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.topologyMode").value("EXTERNAL_CANONICAL"))
                .andExpect(jsonPath("$.externalUrl")
                        .value("https://example.com/repo.git"));

        // Reset topology without using an empty URL, which is now rejected.
        mockMvc.perform(put(BASE + "/configure")
                        .with(csrf())
                        .param("topologyMode", "INTERNAL_SHARED"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "regularuser", roles = "USER")
    void configureIsDeniedForNonAdminUser() throws Exception {
        mockMvc.perform(put(BASE + "/configure")
                        .with(csrf())
                        .param("topologyMode", "EXTERNAL_CANONICAL"))
                .andExpect(status().isForbidden());
    }
}
