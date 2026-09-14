package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real browser startup sequence: metadata selection pins the default before repository reads. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key="
})
class WorkspaceDefaultPinStartupIT {
    @Autowired private MockMvc mvc;
    @Autowired private UserWorkspaceRepository rows;
    @Autowired private WorkspaceManager manager;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void browserPinDoesNotDisableAutomaticDefaultInitialization(boolean header) throws Exception {
        String owner = "startup-" + UUID.randomUUID();
        UserWorkspace workspace = workspace(owner, true, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        try {
            manager.switchWorkspace(owner, workspace.getWorkspaceId());
            mvc.perform(get("/api/workspace/current").with(user(owner).roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId()));
            mvc.perform(pinned("/api/dsl/branches", owner, workspace, header))
                    .andExpect(status().isOk());
            mvc.perform(pinned("/api/proposals/pending", owner, workspace, header))
                    .andExpect(status().isOk());
            var ready = rows.findByWorkspaceId(workspace.getWorkspaceId()).orElseThrow();
            assertEquals(WorkspaceProvisioningStatus.READY, ready.getProvisioningStatus());
            assertEquals("draft", ready.getCurrentBranch());
            assertNotNull(ready.getCurrentCommit());
            assertNotNull(ready.getBaseCommit());
            assertNotNull(ready.getSourceRepositoryId());
        } finally {
            manager.evictWorkspace(owner);
            rows.deleteById(workspace.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rememberedDefaultPinDoesNotSwitchAnotherTabsActiveWorkspace(boolean header) throws Exception {
        String owner = "two-tabs-" + UUID.randomUUID();
        UserWorkspace pinned = workspace(owner, true, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        UserWorkspace active = workspace(owner, false, WorkspaceProvisioningStatus.READY);
        try {
            manager.switchWorkspace(owner, active.getWorkspaceId());
            mvc.perform(pinned("/api/dsl/branches", owner, pinned, header))
                    .andExpect(status().isOk());
            assertEquals(WorkspaceProvisioningStatus.READY,
                    rows.findByWorkspaceId(pinned.getWorkspaceId()).orElseThrow().getProvisioningStatus());
            assertEquals(active.getWorkspaceId(), manager.findActiveWorkspace(owner).getWorkspaceId());
        } finally {
            manager.evictWorkspace(owner);
            rows.deleteById(pinned.getId());
            rows.deleteById(active.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void customOrFailedWorkspacesStillRequireExplicitRecovery(boolean header) throws Exception {
        String owner = "recovery-" + UUID.randomUUID();
        for (var state : new WorkspaceProvisioningStatus[] {
                WorkspaceProvisioningStatus.NOT_PROVISIONED,
                WorkspaceProvisioningStatus.PROVISIONING,
                WorkspaceProvisioningStatus.FAILED}) {
            boolean defaultWorkspace = state != WorkspaceProvisioningStatus.NOT_PROVISIONED;
            UserWorkspace workspace = workspace(owner, defaultWorkspace, state);
            try {
                mvc.perform(pinned("/api/dsl/branches", owner, workspace, header))
                        .andExpect(status().isConflict());
                assertEquals(state, rows.findByWorkspaceId(workspace.getWorkspaceId())
                        .orElseThrow().getProvisioningStatus());
            } finally {
                manager.evictWorkspace(owner);
                rows.deleteById(workspace.getId());
            }
        }
    }

    private static MockHttpServletRequestBuilder pinned(
            String path, String owner, UserWorkspace workspace, boolean header) {
        var request = get(path).with(user(owner).roles("ADMIN"));
        return header ? request.header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace.getWorkspaceId())
                : request.param(WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER, workspace.getWorkspaceId());
    }

    private UserWorkspace workspace(String owner, boolean defaultWorkspace, WorkspaceProvisioningStatus state) {
        var workspace = new UserWorkspace();
        String id = "startup-" + UUID.randomUUID();
        workspace.setWorkspaceId(id);
        workspace.setDisplayName(id);
        workspace.setUsername(owner);
        workspace.setDefault(defaultWorkspace);
        workspace.setProvisioningStatus(state);
        workspace.setCreatedAt(Instant.now());
        workspace.setLastAccessedAt(Instant.now());
        return rows.saveAndFlush(workspace);
    }
}
