package com.taxonomy.security.controller;

import org.junit.jupiter.api.Test;
import com.taxonomy.editor.ArchitectureCommandPort.Context;
import com.taxonomy.editor.ArchitectureEditorController;
import com.taxonomy.editor.ArchitectureEditorProjection;
import com.taxonomy.editor.ArchitectureEditorService;
import com.taxonomy.export.LayeredDiagramLayoutService;
import com.taxonomy.security.access.ArchitectureEditPermissionAdapter;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountContextControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(username = "reader", authorities = {"ROLE_READER", "FACTOR_PASSWORD"})
    void readerContextIsReadOnlyAndContainsOnlyApplicationRoles() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("reader"))
                .andExpect(jsonPath("$.roles", hasSize(1)))
                .andExpect(jsonPath("$.roles[0]").value("READER"))
                .andExpect(jsonPath("$.architectureMutationAllowed").value(false))
                .andExpect(jsonPath("$.analysisExecutionAllowed").value(false))
                .andExpect(jsonPath("$.dataReadAllowed").value(true))
                .andExpect(jsonPath("$.administrator").value(false));
    }

    @Test
    @WithMockUser(username = "architect", roles = {"USER", "ARCHITECT"})
    void architectContextAllowsArchitectureMutation() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[?(@ == 'ARCHITECT')]").exists())
                .andExpect(jsonPath("$.architectureMutationAllowed").value(true))
                .andExpect(jsonPath("$.analysisExecutionAllowed").value(true))
                .andExpect(jsonPath("$.dataReadAllowed").value(true))
                .andExpect(jsonPath("$.administrator").value(false));
    }

    @Test
    @WithMockUser(username = "administrator", roles = {"USER", "ARCHITECT", "ADMIN"})
    void adminContextExposesAdministratorCapability() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architectureMutationAllowed").value(true))
                .andExpect(jsonPath("$.administrator").value(true));
    }
    @Test
    @WithMockUser(username = "administrator", roles = "ADMIN")
    void administratorAloneHasReadAndAdministrationButNoBusinessExecution() throws Exception {
        mockMvc.perform(get("/api/account/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architectureMutationAllowed").value(false))
                .andExpect(jsonPath("$.analysisExecutionAllowed").value(false))
                .andExpect(jsonPath("$.dataReadAllowed").value(true))
                .andExpect(jsonPath("$.administrator").value(true));
    }

    @Test
    void readOnlyArchitectBearerCannotAdvertiseMutationOrAnalysisExecution() throws Exception {
        mockMvc.perform(get("/api/account/me").with(jwt().authorities(
                        AuthorityUtils.createAuthorityList("ROLE_ARCHITECT", "SCOPE_architecture:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architectureMutationAllowed").value(false))
                .andExpect(jsonPath("$.analysisExecutionAllowed").value(false))
                .andExpect(jsonPath("$.dataReadAllowed").value(true));
    }

    @Test
    void architectBearerCapabilitiesRequireTheirSeparateScopes() throws Exception {
        mockMvc.perform(get("/api/account/me").with(jwt().authorities(
                        AuthorityUtils.createAuthorityList("ROLE_ARCHITECT", "SCOPE_architecture:write", "SCOPE_analysis:run"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architectureMutationAllowed").value(true))
                .andExpect(jsonPath("$.analysisExecutionAllowed").value(true))
                .andExpect(jsonPath("$.dataReadAllowed").value(false));
    }

    @Test
    void editorReceivesAppPermissionAdapterAndHonorsBearerScope() throws Exception {
        var service = mock(ArchitectureEditorService.class);
        var resolver = mock(WorkspaceResolver.class);
        var selected = RepositoryContext.workspace("repo-a", "workspace-a", "draft", "alice");
        when(resolver.resolveCurrentRepositoryContext()).thenReturn(selected);
        when(service.read(selected, null, null)).thenReturn(new ArchitectureEditorService.Document(
                Context.of(selected, null), "", "READY", List.of(), List.of(), 0, null, "WORKSPACE_REVISION"));
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("architectureEditorService", service);
            context.getBeanFactory().registerSingleton("workspaceResolver", resolver);
            context.registerBean(ArchitectureEditorProjection.class,
                    () -> new ArchitectureEditorProjection(new LayeredDiagramLayoutService()));
            context.register(ArchitectureEditorController.class, ArchitectureEditPermissionAdapter.class);
            context.refresh();
            var editor = context.getBean(ArchitectureEditorController.class);
            SecurityContextHolder.getContext().setAuthentication(architectBearer("architecture:read"));
            assertThat(editor.read("repo-a", "workspace-a", "draft", null, null).getBody().mayEdit()).isFalse();
            SecurityContextHolder.getContext().setAuthentication(architectBearer("architecture:write"));
            assertThat(editor.read("repo-a", "workspace-a", "draft", null, null).getBody().mayEdit()).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static JwtAuthenticationToken architectBearer(String scope) {
        var token = Jwt.withTokenValue("token").header("alg", "RS256").subject("alice").build();
        return new JwtAuthenticationToken(token,
                AuthorityUtils.createAuthorityList("ROLE_ARCHITECT", "SCOPE_" + scope), "alice");
    }

}
