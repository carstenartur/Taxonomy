package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real app policy injection must prevent working-copy creation through read endpoints. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "taxonomy.git.bootstrap=false")
class WorkspaceReadOnlyProvisioningTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserWorkspaceRepository rows;
    @Autowired private WorkspaceManager manager;
    @Autowired private WorkspaceContextResolver contexts;
    @Autowired private SystemRepositoryService repositories;

    @AfterEach
    void evictNavigation() {
        List.of("reader-without-copy", "admin-without-copy", "architect-read-only-copy",
                "reader-existing-copy", "reader-pending-copy", "analyst-permitted-copy",
                "analyst-wrong-target", "other-provision-target", "system-explicit-copy")
                .forEach(manager::evictWorkspace);
    }

    @Test
    @WithMockUser(username = "reader-without-copy", roles = "READER")
    void readerWithoutWorkspaceGetsCentralReadWithoutAllocation() throws Exception {
        mvc.perform(get("/api/workspace/current")).andExpect(status().isNoContent());
        assertCentralWithoutMetadata("reader-without-copy");
    }

    @Test
    @WithMockUser(username = "admin-without-copy", roles = "ADMIN")
    void administratorAloneCannotLazilyAllocateWorkspace() throws Exception {
        mvc.perform(get("/api/workspace/current")).andExpect(status().isNoContent());
        assertCentralWithoutMetadata("admin-without-copy");
    }

    @Test
    void readOnlyArchitectBearerCannotLazilyAllocateWorkspace() throws Exception {
        mvc.perform(get("/api/workspace/current").with(jwt()
                        .jwt(token -> token.subject("architect-read-only-copy"))
                        .authorities(AuthorityUtils.createAuthorityList("ROLE_ARCHITECT", "SCOPE_architecture:read"))))
                .andExpect(status().isNoContent());
        assertCentralWithoutMetadata("architect-read-only-copy");
    }

    @Test
    @WithMockUser(username = "reader-existing-copy", roles = "READER")
    void readerCanReadExistingReadyWorkspace() throws Exception {
        var workspace = workspace("reader-existing-copy", WorkspaceProvisioningStatus.READY);
        mvc.perform(get("/api/workspace/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(workspace.getWorkspaceId()))
                .andExpect(jsonPath("$.provisioningStatus").value("READY"));
        assertThat(contexts.resolveRepositoryContextForUser("reader-existing-copy").workspaceId())
                .isEqualTo(workspace.getWorkspaceId());
    }

    @Test
    @WithMockUser(username = "reader-pending-copy", roles = "READER")
    void pendingDefaultWorkspaceIsNotProvisionedAndDoesNotBlockCentralReads() throws Exception {
        var pending = workspace("reader-pending-copy", WorkspaceProvisioningStatus.NOT_PROVISIONED);
        mvc.perform(get("/api/workspace/current")).andExpect(status().isNoContent());
        assertThat(contexts.resolveRepositoryContextForUser("reader-pending-copy").scope())
                .isEqualTo(RepositoryScope.CENTRAL_READ);
        assertThat(pending.getProvisioningStatus()).isEqualTo(WorkspaceProvisioningStatus.NOT_PROVISIONED);
        assertThat(pending.getCurrentCommit()).isNull();
    }

    @Test
    @WithMockUser(username = "analyst-permitted-copy", roles = "USER")
    void analystRetainsDefaultMetadataCreationCapability() {
        assertThat(manager.getWorkspaceInfo("analyst-permitted-copy")).isNotNull();
        assertThat(rows.findByUsernameAndSharedFalse("analyst-permitted-copy")).isPresent();
    }

    @Test
    @WithMockUser(username = "analyst-wrong-target", roles = "USER")
    void currentActorsCreationCapabilityCannotBeBorrowedForAnotherUser() {
        assertThat(manager.getWorkspaceInfo("other-provision-target")).isNull();
        assertThat(rows.findByUsernameAndSharedFalse("other-provision-target")).isEmpty();
    }

    @Test
    void explicitSystemMetadataCreationStillWorksWithoutAuthentication() {
        var created = manager.createWorkspace("system-explicit-copy", "System working copy", "Explicit internal operation");
        assertThat(rows.findByWorkspaceId(created.getWorkspaceId())).isPresent();
    }

    private void assertCentralWithoutMetadata(String username) {
        assertThat(rows.findByUsernameAndSharedFalse(username)).isEmpty();
        var context = contexts.resolveRepositoryContextForUser(username);
        assertThat(context.scope()).isEqualTo(RepositoryScope.CENTRAL_READ);
        assertThat(context.workspaceId()).isNull();
        assertThat(context.repositoryId()).isEqualTo(repositories.getPrimaryRepository().getRepositoryId());
    }

    private UserWorkspace workspace(String username, WorkspaceProvisioningStatus status) {
        var workspace = new UserWorkspace();
        workspace.setUsername(username);
        workspace.setWorkspaceId(username + "-existing");
        workspace.setDisplayName(username + " workspace");
        workspace.setDefault(true);
        workspace.setCreatedAt(Instant.now());
        workspace.setSourceRepositoryId(repositories.getPrimaryRepository().getRepositoryId());
        workspace.setProvisioningStatus(status);
        return rows.saveAndFlush(workspace);
    }
}
