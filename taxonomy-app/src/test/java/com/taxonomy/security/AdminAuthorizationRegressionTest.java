package com.taxonomy.security;

import com.taxonomy.dsl.command.ArchitectureDslCommands;
import com.taxonomy.security.service.DatabaseUserDetailsService;
import com.taxonomy.security.service.UserManagementService;
import com.taxonomy.support.AnalysisCatalogueSourceFixture;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.ArchitectureRepositoryProvisioningService;
import com.taxonomy.workspace.service.RepositoryWorkspaceService;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:hsqldb:mem:admin-authorization-regression",
        "embedding.enabled=false",
        "gemini.api.key=",
        "openai.api.key=",
        "deepseek.api.key=",
        "qwen.api.key=",
        "llama.api.key=",
        "mistral.api.key="
})
class AdminAuthorizationRegressionTest implements AnalysisCatalogueSourceFixture {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DatabaseUserDetailsService users;

    @Autowired
    private UserManagementService accounts;

    @Autowired
    private ArchitectureRepositoryProvisioningService repositoryProvisioning;

    @Autowired
    private RepositoryWorkspaceService repositoryWorkspaces;

    @Autowired
    private DslGitRepositoryFactory gitRepositories;

    @Test
    @WithMockUser(username = "reader", roles = "USER")
    void nonAdminSeesLockedAdminUiAndCannotReadPrompts() throws Exception {
        mockMvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordRequired").value(true));

        mockMvc.perform(get("/api/prompts"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "administrator", roles = "ADMIN")
    void roleAdminIsAuthorizedWithoutSecondApplicationPassword() throws Exception {
        mockMvc.perform(get("/api/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordRequired").value(false));

        mockMvc.perform(post("/api/admin/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.token").value("role-admin"));
    }

    @Test
    void architectCanReachWorkspacePullAndPublishOperations() throws Exception {
        String architect = "publication-architect-" + UUID.randomUUID();
        accounts.createUser(Map.of("username", architect, "roles", List.of("ARCHITECT")),
                UUID.randomUUID().toString(), "test");
        var principal = users.loadUserByUsername(architect);
        String sourceDsl = """
                element authorization-capability type Capability {
                  title: "Authorization capability";
                }
                element authorization-service type CoreService {
                  title: "Authorization service";
                }
                relation authorization-capability REALIZES authorization-service {
                  status: accepted;
                }
                """;
        var commands = new ArchitectureDslCommands();
        var model = commands.model(sourceDsl);
        assertThat(model.getElements()).hasSize(2);
        assertThat(model.getRelations()).hasSize(1);
        commands.validateReconciledSource(sourceDsl);

        // Authorization must reach valid backend work, independent of the shared test Git history.
        var source = repositoryProvisioning.createRepository(
                "Authorization regression", "authorization-" + UUID.randomUUID(),
                "Isolated authorization fixture", RepositoryVisibility.PRIVATE, architect, "draft");
        var sourceGit = gitRepositories.getCentralRepository(source.getRepositoryId());
        sourceGit.commitDsl(source.getDefaultBranch(), sourceDsl, architect, "Valid authorization source");
        var workspace = repositoryWorkspaces.createWorkingCopy(
                architect, source.getRepositoryId(), source.getDefaultBranch(),
                "Authorization working copy", "Isolated authorization fixture");
        var workspaceGit = gitRepositories.openWorkspaceRepository(workspace.getWorkspaceId());
        String branch = workspace.getCurrentBranch();

        var pull = mockMvc.perform(post("/api/workspace/sync-from-shared")
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace.getWorkspaceId())
                        .param("userBranch", branch)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.branch").value(branch));
        String pulledCommit = workspaceGit.getHeadCommit(branch);
        assertThat(pulledCommit).matches("[0-9a-f]{40}");
        pull.andExpect(jsonPath("$.mergeCommit").value(pulledCommit));
        commands.validateReconciledSource(workspaceGit.getDslAtCommit(pulledCommit));

        var publish = mockMvc.perform(post("/api/workspace/publish")
                        .header(WorkspaceContextResolver.WORKSPACE_HEADER, workspace.getWorkspaceId())
                        .param("userBranch", branch)
                        .with(user(principal)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.branch").value(branch));
        String publishedCommit = sourceGit.getHeadCommit(source.getDefaultBranch());
        assertThat(publishedCommit).matches("[0-9a-f]{40}");
        publish.andExpect(jsonPath("$.mergeCommit").value(publishedCommit));
        commands.validateReconciledSource(sourceGit.getDslAtCommit(publishedCommit));
    }

    @Test
    @WithMockUser(username = "reader", roles = "USER")
    void readerCannotPublishArchitectureChangesToShared() throws Exception {
        mockMvc.perform(post("/api/workspace/publish").with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void formLoginSessionRequiresCsrfForStateChangingApiCalls() throws Exception {
        MockHttpSession session = new MockHttpSession();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        var admin = users.loadUserByUsername("admin");
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                admin, null, admin.getAuthorities()));
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                securityContext);

        mockMvc.perform(post("/api/admin/verify")
                        .session(session)
                        .with(csrf().useInvalidToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/verify")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    @WithMockUser(username = "reader", roles = "USER")
    void systemInformationIsNotReadableByNonAdministrators() throws Exception {
        mockMvc.perform(get("/api/admin/system-information"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "administrator", roles = "ADMIN")
    void systemInformationIsAvailableToAdministratorsWithoutClientCaching() throws Exception {
        mockMvc.perform(get("/api/admin/system-information"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database.versionSource").value("DATABASE_QUERY"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Cache-Control"))
                        .contains("no-store"));
    }

    @Test
    void systemInformationIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/admin/system-information").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"USER,GET", "USER,HEAD", "ARCHITECT,GET", "ARCHITECT,HEAD"})
    void workspaceActivityDoesNotExposeGlobalMetadataToNonAdmins(String role, String method) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), "/api/workspace/active")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("activity-reader").roles(role)))
                .andExpect(status().isForbidden());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GET", "HEAD"})
    void workspaceActivityRemainsAvailableToAdmins(String method) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), "/api/workspace/active")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("activity-admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"GET", "HEAD"})
    void workspaceActivityIsNotPublic(String method) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(method), "/api/workspace/active")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

}
