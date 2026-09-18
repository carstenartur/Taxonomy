package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC, JPA and JGit boundaries, including two browser tabs choosing different workspaces. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "provisioning-boundary-user", roles = "ADMIN")
@TestPropertySource(properties = {
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key=",
        "taxonomy.features.multi-repository-api.enabled=false"
})
class WorkspaceProvisioningBoundaryIT {
    private static final String USER = "provisioning-boundary-user";
    private static final String HEADER = WorkspaceContextResolver.WORKSPACE_HEADER;
    private static final String QUERY = WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER;

    @Autowired private MockMvc mvc;
    @Autowired private UserWorkspaceRepository rows;
    @Autowired private WorkspaceManager manager;
    @Autowired private SystemRepositoryService repositories;

    @AfterEach
    void clearNavigation() {
        manager.evictWorkspace(USER);
    }

    @ParameterizedTest
    @EnumSource(value = WorkspaceProvisioningStatus.class,
            names = {"NOT_PROVISIONED", "PROVISIONING", "FAILED"})
    void unfinishedWorkspaceRejectsRepositoryReadsButAllowsLifecycleMetadata(
            WorkspaceProvisioningStatus state) throws Exception {
        UserWorkspace workspace = workspace(USER, state);
        manager.switchWorkspace(USER, workspace.getWorkspaceId());
        mvc.perform(get("/api/dsl/git/head").header(HEADER, workspace.getWorkspaceId()))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/workspace/{id}/info", workspace.getWorkspaceId())
                        .header(HEADER, workspace.getWorkspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId()));
        mvc.perform(get("/api/workspace/current").header(HEADER, workspace.getWorkspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioningStatus").value(state.name()));
        assertThat(rows.findByWorkspaceId(workspace.getWorkspaceId()).orElseThrow()
                .getProvisioningStatus()).isEqualTo(state);
    }

    @ParameterizedTest
    @CsvSource({"NOT_PROVISIONED, HEADER", "NOT_PROVISIONED, QUERY", "FAILED, HEADER", "FAILED, QUERY"})
    void provisioningTargetsThePinnedWorkspaceRatherThanAnotherTabsActiveSelection(
            WorkspaceProvisioningStatus state, String transport) throws Exception {
        UserWorkspace active = workspace(USER, WorkspaceProvisioningStatus.READY);
        active.setCurrentBranch("feature/other-tab");
        rows.saveAndFlush(active);
        manager.switchWorkspace(USER, active.getWorkspaceId());
        UserWorkspace pinned = workspace(USER, state);
        MockHttpServletRequestBuilder request = post("/api/workspace/provision").with(csrf());
        if ("HEADER".equals(transport)) {
            request.header(HEADER, pinned.getWorkspaceId());
        } else {
            request.param(QUERY, pinned.getWorkspaceId());
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        UserWorkspace provisioned = rows.findByWorkspaceId(pinned.getWorkspaceId()).orElseThrow();
        assertThat(provisioned.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.READY);
        assertThat(provisioned.getCurrentBranch()).isEqualTo("main");
        assertThat(rows.findByWorkspaceId(active.getWorkspaceId()).orElseThrow().getCurrentBranch())
                .isEqualTo("feature/other-tab");
        assertThat(manager.findActiveWorkspace(USER).getWorkspaceId()).isEqualTo(active.getWorkspaceId());
    }

    @Test
    void provisioningHeaderTakesPrecedenceOverQuery() throws Exception {
        UserWorkspace pending = workspace(USER, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        UserWorkspace other = workspace("foreign-provisioning-user", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        mvc.perform(post("/api/workspace/provision").with(csrf())
                        .header(HEADER, pending.getWorkspaceId()).param(QUERY, other.getWorkspaceId()))
                .andExpect(status().isOk());
        assertThat(pending.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.READY);
        assertThat(other.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.NOT_PROVISIONED);
    }

    @Test
    void inProgressProvisioningIsNotRestarted() throws Exception {
        UserWorkspace pending = workspace(USER, WorkspaceProvisioningStatus.PROVISIONING);
        manager.switchWorkspace(USER, pending.getWorkspaceId());
        mvc.perform(post("/api/workspace/provision").with(csrf()).header(HEADER, pending.getWorkspaceId()))
                .andExpect(status().isConflict());
        assertThat(pending.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.PROVISIONING);
        assertThat(pending.getCurrentBranch()).isEqualTo("draft");
    }

    @Test
    void foreignLifecyclePinStillFailsBeforeProvisioning() throws Exception {
        UserWorkspace foreign = workspace("foreign-provisioning-user", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        mvc.perform(post("/api/workspace/provision").with(csrf()).header(HEADER, foreign.getWorkspaceId()))
                .andExpect(status().isForbidden());
        assertThat(foreign.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.NOT_PROVISIONED);
    }

    @Test
    void unpinnedProvisioningStillInitializesTheActiveWorkspace() throws Exception {
        UserWorkspace pending = workspace(USER, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        manager.switchWorkspace(USER, pending.getWorkspaceId());
        mvc.perform(post("/api/workspace/provision").with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        assertThat(pending.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.READY);
    }

    @Test
    void currentDefaultWorkspaceResponseIsReadyForItsBrowserPin() throws Exception {
        // Persist the automatically created metadata before the HTTP request,
        // just as a completed first-login transaction does in the browser flow.
        UserWorkspace pending = workspace(USER, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        pending.setDefault(true);
        rows.saveAndFlush(pending);
        var response = mvc.perform(get("/api/workspace/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioningStatus").value("READY"))
                .andExpect(jsonPath("$.currentBranch").value("draft"))
                .andReturn().getResponse();
        String id = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.getContentAsString()).get("workspaceId").textValue();
        var workspace = rows.findByWorkspaceId(id).orElseThrow();
        assertThat(workspace.isDefault()).isTrue();
        assertThat(workspace.getCurrentCommit()).isNotBlank();
        mvc.perform(get("/api/dsl/branches").header(HEADER, id)).andExpect(status().isOk());
        mvc.perform(get("/api/proposals/pending").header(HEADER, id)).andExpect(status().isOk());
    }

    @Test
    void explicitlyPinnedDefaultMetadataDoesNotTriggerProvisioning() throws Exception {
        UserWorkspace pending = workspace(USER, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        pending.setDefault(true);
        rows.saveAndFlush(pending);
        mvc.perform(get("/api/workspace/current").header(HEADER, pending.getWorkspaceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioningStatus").value("NOT_PROVISIONED"));
        assertThat(pending.getCurrentCommit()).isNull();
        mvc.perform(get("/api/dsl/branches").header(HEADER, pending.getWorkspaceId()))
                .andExpect(status().isConflict());
    }

    @Test
    void unpinnedNonDefaultMetadataDoesNotTriggerProvisioning() throws Exception {
        UserWorkspace pending = workspace(USER, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        manager.switchWorkspace(USER, pending.getWorkspaceId());
        mvc.perform(get("/api/workspace/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provisioningStatus").value("NOT_PROVISIONED"));
        assertThat(pending.getCurrentCommit()).isNull();
    }

    private UserWorkspace workspace(String owner, WorkspaceProvisioningStatus state) {
        UserWorkspace workspace = new UserWorkspace();
        String id = "provisioning-boundary-" + UUID.randomUUID();
        workspace.setWorkspaceId(id);
        workspace.setDisplayName(id);
        workspace.setUsername(owner);
        workspace.setSourceRepositoryId(repositories.getPrimaryRepository().getRepositoryId());
        workspace.setProvisioningStatus(state);
        workspace.setCreatedAt(Instant.now());
        workspace.setLastAccessedAt(Instant.now());
        return rows.saveAndFlush(workspace);
    }
}
