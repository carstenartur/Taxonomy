package com.taxonomy.workspace.controller;

import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.model.RepositoryRole;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MVC, JPA and JGit boundaries, including two browser tabs choosing different workspaces. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "provisioning-boundary-user", roles = {"USER", "ARCHITECT", "ADMIN"})
@TestPropertySource(properties = {
        "gemini.api.key=", "openai.api.key=", "deepseek.api.key=",
        "qwen.api.key=", "llama.api.key=", "mistral.api.key=",
        "taxonomy.features.multi-repository-api.enabled=false",
        "taxonomy.git.bootstrap=false"
})
class WorkspaceProvisioningBoundaryIT {
    private static final String USER = "provisioning-boundary-user";
    private static final String HEADER = WorkspaceContextResolver.WORKSPACE_HEADER;
    private static final String QUERY = WorkspaceContextResolver.WORKSPACE_QUERY_PARAMETER;
    private static final String SOURCE_DSL = "meta { language: \"taxdsl\"; namespace: \"provisioning-boundary\"; }\n";

    @Autowired private MockMvc mvc;
    @Autowired private UserWorkspaceRepository rows;
    @Autowired private WorkspaceManager manager;
    @Autowired private SystemRepositoryService repositories;
    @Autowired private DslGitRepositoryFactory gitRepositories;
    @Autowired private RepositoryMembershipService memberships;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.taxonomy.security.repository.UserRepository users;
    private SystemRepository source;
    private String sourceCommit;
    private final List<String> workspaceIds = new ArrayList<>();

    @BeforeEach
    void createOwnedSourceCheckpoint() throws Exception {
        // A prior Spring context may already have consumed the JVM-wide bootstrap.
        // Own a unique real source instead of depending on the primary's test order.
        source = repositories.createCentralRepository("Provisioning boundary source",
                "provisioning-boundary-" + UUID.randomUUID(), null,
                RepositoryVisibility.PRIVATE, USER, "draft");
        var git = gitRepositories.createCentralRepository(
                source.getRepositoryId(), source.getStorageRepositoryName());
        sourceCommit = git.commitDsl("draft", SOURCE_DSL, USER, "Provisioning test source");
        repositories.markProvisioningReady(source.getRepositoryId());
        assertThat(git.getDslAtCommit(sourceCommit)).isEqualTo(SOURCE_DSL);
    }

    @AfterEach
    void clearNavigation() {
        try {
            manager.evictWorkspace(USER);
        } finally {
            try {
                // JPA rollback does not replace deletion of separately persisted Git state.
                workspaceIds.forEach(gitRepositories::deleteWorkspaceRepository);
            } finally {
                if (source != null) gitRepositories.deleteCentralRepository(source.getRepositoryId());
            }
        }
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
        assertThat(provisioned.getSourceRepositoryId()).isEqualTo(source.getRepositoryId());
        assertThat(provisioned.getBaseCommit()).isEqualTo(sourceCommit);
        assertThat(gitRepositories.openWorkspaceRepository(provisioned.getWorkspaceId())
                .getDslAtCommit(provisioned.getCurrentCommit())).isEqualTo(SOURCE_DSL);
        assertThat(rows.findByWorkspaceId(active.getWorkspaceId()).orElseThrow().getCurrentBranch())
                .isEqualTo("feature/other-tab");
        assertThat(manager.findActiveWorkspace(USER).getWorkspaceId()).isEqualTo(active.getWorkspaceId());
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void twoAuthenticatedActorsJoinOnlyAfterOwnerOptInAndKeepPersonalUndo() throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        // Each HTTP call must finish its own transaction; the sharing lock survives to that commit.
        String ownerUser=USER+"-"+UUID.randomUUID();
        UserWorkspace copy = workspace(ownerUser, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        String id = copy.getWorkspaceId(), bob = "collaboration-contributor-"+UUID.randomUUID();
        var ownerActor=activeActor(ownerUser,"ARCHITECT"); var bobActor=activeActor(bob,"ARCHITECT");
        memberships.assignRole(source.getRepositoryId(),ownerUser,RepositoryRole.CONTRIBUTOR,USER);
        try {
            memberships.assignRole(source.getRepositoryId(), bob, RepositoryRole.CONTRIBUTOR, USER);
            mvc.perform(post("/api/workspace/provision").with(csrf()).header(HEADER,id).with(user(ownerActor)))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/architecture/editor").header(HEADER, id).with(user(bobActor)))
                    .andExpect(status().isForbidden());
            var owner = json.readTree(mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(ownerActor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.collaboration.enabled").value(false))
                    .andExpect(jsonPath("$.collaboration.mayManage").value(true)).andReturn().getResponse().getContentAsString());
            var privateContext = owner.path("document").path("context");
            mvc.perform(post("/api/architecture/editor/collaboration").header(HEADER,id).with(user(ownerActor)).with(csrf())
                    .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("context",privateContext,"enabled",true))))
                    .andExpect(status().isPreconditionRequired());
            mvc.perform(post("/api/architecture/editor/collaboration").header(HEADER,id).with(user(ownerActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-0\"").contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("context",privateContext,"enabled",true))))
                    .andExpect(status().isOk());
            var joined = json.readTree(mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(bobActor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.document.context.writeMode").value("SHARED_WORKSPACE"))
                    .andExpect(jsonPath("$.mayEdit").value(true))
                    .andExpect(jsonPath("$.collaboration.mayManage").value(false)).andReturn().getResponse().getContentAsString());
            var staleBob = joined.path("document").path("context");
            // The same authenticated principal object is reused after database revocation.
            manager.switchWorkspace(ownerUser,id);
            jdbc.update("update app_user set enabled=false where username=?",ownerUser);
            mvc.perform(get("/api/architecture/editor").with(user(ownerActor)))
                    .andExpect(status().isForbidden());
            jdbc.update("update app_user set enabled=true where username=?",ownerUser);
            mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(bob).roles("ARCHITECT")))
                    .andExpect(status().isForbidden()); // an unmapped legacy session cannot join
            mvc.perform(post("/api/architecture/editor/collaboration").header(HEADER,id).with(user(bobActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-0\"").contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("context",staleBob,"enabled",false))))
                    .andExpect(status().isForbidden());
            var ownerShared = json.readTree(mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(ownerActor)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("document").path("context");
            var aliceAccepted = json.readTree(mvc.perform(post("/api/architecture/editor/commands").header(HEADER,id).with(user(ownerActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-0\"").contentType("application/json")
                    .content(json.writeValueAsString(editorCreate(ownerShared,"First collaborator element"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            mvc.perform(post("/api/architecture/editor/commands").header(HEADER,id).with(user(bobActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-0\"").contentType("application/json")
                    .content(json.writeValueAsString(editorCreate(staleBob,"Stale contributor intent"))))
                    .andExpect(status().isPreconditionFailed());
            var bobCurrent = json.readTree(mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(bobActor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.document.history[0].actor").value(ownerUser))
                    .andReturn().getResponse().getContentAsString()).path("document").path("context");
            var bobAccepted = json.readTree(mvc.perform(post("/api/architecture/editor/commands").header(HEADER,id).with(user(bobActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-1\"").contentType("application/json")
                    .content(json.writeValueAsString(editorCreate(bobCurrent,"Second collaborator element"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var inverse = new java.util.LinkedHashMap<String,Object>();
            inverse.put("context",bobAccepted.path("context")); inverse.put("metadata",editorMetadata());
            inverse.put("kind","UNDO"); inverse.put("targetOperationId",aliceAccepted.path("operationId").asString());
            mvc.perform(post("/api/architecture/editor/commands").header(HEADER,id).with(user(bobActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-2\"").contentType("application/json").content(json.writeValueAsString(inverse)))
                    .andExpect(status().isNotFound());
            inverse.put("targetOperationId",bobAccepted.path("operationId").asString()); inverse.put("metadata",editorMetadata());
            mvc.perform(post("/api/architecture/editor/commands").header(HEADER,id).with(user(bobActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-2\"").contentType("application/json").content(json.writeValueAsString(inverse)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.context.revision").value(3));
            jdbc.update("update app_user set enabled=false where username=?",bob);
            mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(bobActor)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/architecture/editor.svg").header(HEADER,id).with(user(bobActor))
                            .param("repositoryId",source.getRepositoryId()).param("workspaceScopeKey",id)
                            .param("branch",copy.getCurrentBranch()).param("revision","3"))
                    .andExpect(status().isForbidden());
            jdbc.update("update app_user set enabled=true where username=?",bob);
            memberships.assignRole(source.getRepositoryId(),bob,RepositoryRole.READER,USER);
            mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(bobActor)))
                    .andExpect(status().isForbidden());
            memberships.assignRole(source.getRepositoryId(),bob,RepositoryRole.CONTRIBUTOR,USER);
            var latest = json.readTree(mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(ownerActor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.model.elements.length()").value(1))
                    .andReturn().getResponse().getContentAsString()).path("document").path("context");
            mvc.perform(post("/api/architecture/editor/collaboration").header(HEADER,id).with(user(ownerActor)).with(csrf())
                    .header("If-Match","\"workspace-revision-3\"").contentType("application/json")
                    .content(json.writeValueAsString(java.util.Map.of("context",latest,"enabled",false))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(bobActor)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/architecture/editor").header(HEADER,id).with(user(ownerActor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.document.context.writeMode").value("PRIVATE_WORKSPACE"))
                    .andExpect(jsonPath("$.document.history.length()").value(1));
        } finally {
            manager.evictWorkspace(ownerUser);
            for (String scope:jdbc.queryForList("select scope_id from editor_workspace where workspace_id=?",String.class,id)) {
                jdbc.update("delete from editor_checkpoint where scope_id=?",scope);
                jdbc.update("delete from editor_operation where scope_id=?",scope);
            }
            jdbc.update("delete from editor_workspace where workspace_id=?",id);
            jdbc.update("delete from architecture_commit_index where workspace_id=?",id);
            jdbc.update("delete from sync_state where workspace_id=?",id);
            gitRepositories.deleteWorkspaceRepository(id); workspaceIds.remove(id);
            rows.deleteById(copy.getId());
            gitRepositories.deleteCentralRepository(source.getRepositoryId());
            jdbc.update("delete from repository_membership where repository_id=?",source.getRepositoryId());
            jdbc.update("delete from system_repository where repository_id=?",source.getRepositoryId());
            source=null;
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"read", "status", "save", "reset", "delete"})
    void unpinnedSharedOwnerDraftRequestsRejectAnArchivedSource(String operation) throws Exception {
        var copy=workspace(USER,WorkspaceProvisioningStatus.READY);
        assertThat(rows.updateCollaborationFlag(copy.getWorkspaceId(),true)).isEqualTo(1);
        copy.setCollaborative(true);
        var owner=activeActor(USER,"USER");
        String path="/api/analysis-drafts/"+copy.getWorkspaceId();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).with(user(owner)).with(csrf())
                .contentType("application/json").content("{\"payload\":{\"businessText\":\"Retained owner draft\"}}"))
                .andExpect(status().isOk());
        source.setLifecycleState(com.taxonomy.workspace.model.RepositoryLifecycleState.ARCHIVED);
        MockHttpServletRequestBuilder request=switch(operation) {
            case "read" -> get(path);
            case "status" -> get(path+"/status");
            case "save" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                    .contentType("application/json").content("{\"payload\":{\"businessText\":\"Changed after archive\"},\"expectedVersion\":0}");
            case "reset" -> post(path+"/reset").contentType("application/json").content("{}");
            case "delete" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path).param("expectedVersion","0");
            default -> throw new AssertionError(operation);
        };
        mvc.perform(request.with(user(owner)).with(csrf())).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"READER", "USER", "ADMIN"})
    void ownershipDoesNotGrantTheArchitectureWriteCapabilityForSharing(String role) throws Exception {
        var copy = workspace(USER, WorkspaceProvisioningStatus.NOT_PROVISIONED);
        String id = copy.getWorkspaceId();
        mvc.perform(post("/api/workspace/provision").header(HEADER, id).with(csrf()))
                .andExpect(status().isOk());
        var owner = activeActor(USER, role);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var view = json.readTree(mvc.perform(get("/api/architecture/editor").header(HEADER, id).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mayEdit").value(false))
                .andExpect(jsonPath("$.collaboration.enabled").value(false))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(post("/api/architecture/editor/collaboration").header(HEADER, id).with(user(owner)).with(csrf())
                .header("If-Match", "\"workspace-revision-0\"").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("context", view.path("document").path("context"), "enabled", true))))
                .andExpect(status().isForbidden());

        assertThat(rows.findByWorkspaceId(id).orElseThrow().isCollaborative()).isFalse();
        mvc.perform(get("/api/architecture/editor").header(HEADER, id).with(user(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.context.revision").value(0))
                .andExpect(jsonPath("$.collaboration.enabled").value(false));
    }

    private com.taxonomy.security.model.PrincipalUserDetails activeActor(String username,String role) {
        var account=new com.taxonomy.security.model.AppUser(); account.setUsername(username); account.setPasswordHash("unused");
        users.saveAndFlush(account);
        String principal=account.getPrincipalId();
        jdbc.update("insert into app_principal(principal_id,scope_key,enabled) values(?,?,1)",principal,username);
        String installation=jdbc.queryForObject("select installation_id from principal_installation where registry_key='local'",String.class);
        var binding=new com.taxonomy.backup.IdentityBinding(com.taxonomy.backup.IdentityBinding.Kind.LOCAL,installation,principal);
        jdbc.update("insert into principal_binding(binding_key,principal_id,binding_kind,issuer,subject_id,enabled) values(?,?,'LOCAL',?,?,1)",
                com.taxonomy.security.model.PrincipalBinding.key(binding),principal,installation,principal);
        return new com.taxonomy.security.model.PrincipalUserDetails(new com.taxonomy.backup.PrincipalId(UUID.fromString(principal)),
                username,"unused",true,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role)));
    }

    private static java.util.Map<String,Object> editorCreate(Object context,String title) {
        return java.util.Map.of("context",context,"metadata",editorMetadata(),"kind","CREATE_ELEMENT","type","Capability","properties",java.util.Map.of("title",title));
    }
    private static java.util.Map<String,String> editorMetadata() {
        String id=UUID.randomUUID().toString(); return java.util.Map.of("commandId",id,"correlationId",id,"causationId",id,"rationale","Reviewed shared architecture edit");
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
        assertThat(workspace.getSourceRepositoryId()).isEqualTo(source.getRepositoryId());
        assertThat(workspace.getBaseCommit()).isEqualTo(sourceCommit);
        assertThat(gitRepositories.openWorkspaceRepository(id)
                .getDslAtCommit(workspace.getCurrentCommit())).isEqualTo(SOURCE_DSL);
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
        workspaceIds.add(id);
        workspace.setWorkspaceId(id);
        workspace.setDisplayName(id);
        workspace.setUsername(owner);
        workspace.setSourceRepositoryId(source.getRepositoryId());
        workspace.setProvisioningStatus(state);
        workspace.setCreatedAt(Instant.now());
        workspace.setLastAccessedAt(Instant.now());
        return rows.saveAndFlush(workspace);
    }
}
