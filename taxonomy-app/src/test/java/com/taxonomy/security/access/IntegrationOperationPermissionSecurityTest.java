package com.taxonomy.security.access;

import com.taxonomy.extension.api.integration.IntegrationContracts.AuthorityMode;
import com.taxonomy.extension.api.integration.IntegrationContracts.ExternalScope;
import com.taxonomy.extension.api.integration.IntegrationContracts.IntegrationContext;
import com.taxonomy.extension.api.integration.IntegrationContracts.InternalState;
import com.taxonomy.extension.api.integration.IntegrationContracts.OperationStatus;
import com.taxonomy.interop.IntegrationService;
import com.taxonomy.interop.controller.IntegrationController;
import com.taxonomy.interop.persistence.IntegrationStore;
import com.taxonomy.interop.publication.IntegrationPublicationService;
import com.taxonomy.security.config.AuthorizationRulesConfigurer;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceAccessService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real request filter and integration dispatcher, with retained operation and remote service seams. */
class IntegrationOperationPermissionSecurityTest {
    private static final RepositoryContext REPOSITORY = RepositoryContext.workspace("repo", "workspace", "draft", "alice");
    private static final UUID CONNECTION = UUID.fromString("0a837cbe-63be-4cbf-8af6-510a26838323");
    private static final UUID OPERATION = UUID.fromString("fd3769b4-e4ed-4db4-a5e5-8543e7188112");
    private static final String BASE = "/api/integrations/" + CONNECTION;
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private IntegrationStore store;
    private IntegrationPublicationService publication;

    @BeforeAll
    static void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Fixture.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll
    static void stop() { if (context != null) context.close(); }

    @BeforeEach
    void retainedOperation() {
        store = context.getBean(IntegrationStore.class);
        publication = context.getBean(IntegrationPublicationService.class);
        reset(store, publication);
    }

    @Test
    void importScopedArchitectCannotPublishRemotely() throws Exception {
        mvc.perform(post(BASE + "/publish").with(csrf()).with(token("ARCHITECT", "integration:import"))
                        .contentType("application/json").content(review()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    @Test
    void publishScopedArchitectCanPublishWithoutTechnicalAdministration() throws Exception {
        mvc.perform(post(BASE + "/publish").with(csrf()).with(token("ARCHITECT", "architecture:publish"))
                        .contentType("application/json").content(review()))
                .andExpect(status().isOk());
        verify(publication).publish(eq(REPOSITORY), eq(CONNECTION), any());
    }

    @Test
    void retainedPublicationCannotBeRetriedWithOnlyImportScope() throws Exception {
        for (String direction : List.of("PUSH", "SYNCHRONIZE")) {
            operation(direction);
            mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "integration:import")))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(publication);
    }

    @Test
    void retainedPublicationCannotBeRetriedWithOnlyExportScope() throws Exception {
        operation("PUSH");
        mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "integration:export")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    @Test
    void technicalAdministratorCannotUseExportAdmissionToResumeBusinessPublication() throws Exception {
        operation("PUSH");
        mvc.perform(post(retry()).with(csrf()).with(jwt().jwt(jwt -> jwt.subject(REPOSITORY.username())).authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("SCOPE_integration:export"),
                        new SimpleGrantedAuthority("SCOPE_architecture:publish"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    @Test
    void operationActorMustMatchTheAuthenticatedPublisher() throws Exception {
        operation("PUSH");
        mvc.perform(post(retry()).with(csrf()).with(jwt().jwt(jwt -> jwt.subject("another-actor")).authorities(
                        new SimpleGrantedAuthority("ROLE_ARCHITECT"), new SimpleGrantedAuthority("SCOPE_architecture:publish"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    @Test
    void publicationRetryNeedsNoImportOrTechnicalAdministrationScope() throws Exception {
        operation("SYNCHRONIZE");
        mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "architecture:publish")))
                .andExpect(status().isOk());
        verify(publication).retryPublication(REPOSITORY, CONNECTION, OPERATION);
    }

    @Test
    void importRetryRetainsItsOwnCapability() throws Exception {
        operation("INBOUND");
        mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "integration:import")))
                .andExpect(status().isOk());
        verifyNoInteractions(publication);
    }

    @Test
    void fileExportRetryRetainsItsOwnCapabilityWithoutPublicationOrArchitectRole() throws Exception {
        operation("OUTBOUND");
        mvc.perform(post(retry()).with(csrf()).with(token("READER", "integration:export")))
                .andExpect(status().isOk());
        verifyNoInteractions(publication);
    }

    @Test
    void importScopeCannotRetryAFileExport() throws Exception {
        operation("OUTBOUND");
        mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "integration:import")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    @Test
    void publicationScopeCannotRetryAnImport() throws Exception {
        operation("INBOUND");
        mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "architecture:publish")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    @Test
    void exportScopeCannotRetryAnImport() throws Exception {
        operation("INBOUND");
        mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "integration:export")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    @Test
    void unknownPersistedOperationKindCannotBeRetried() throws Exception {
        operation("UNKNOWN");
        mvc.perform(post(retry()).with(csrf()).with(token("ARCHITECT", "integration:import")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(publication);
    }

    private static String retry() { return BASE + "/operations/" + OPERATION + "/retry"; }

    private static String review() {
        return "{\"review\":{\"operationId\":\"" + OPERATION
                + "\",\"previewFingerprint\":\"reviewed-preview\",\"decisions\":{},\"rationale\":\"Reviewed publication\"},\"resolutions\":{}}";
    }

    private static RequestPostProcessor token(String role, String scope) {
        return jwt().jwt(jwt -> jwt.subject(REPOSITORY.username())).authorities(
                new SimpleGrantedAuthority("ROLE_" + role), new SimpleGrantedAuthority("SCOPE_" + scope));
    }

    private void operation(String direction) {
        var state = new InternalState("repo", "workspace:workspace", "draft", "a".repeat(40), 1L, null, null);
        var authority = new IntegrationContext(CONNECTION, AuthorityMode.BIDIRECTIONAL,
                new ExternalScope("fixture", "urn:fixture:model", null), state, REPOSITORY.username(), "fixture", "1");
        when(store.operation(REPOSITORY, CONNECTION, OPERATION)).thenReturn(new IntegrationStore.Operation(
                OPERATION, CONNECTION, authority, direction, OperationStatus.COMPLETED, "frozen-request", 0L,
                null, List.of(), null, null, null, null, null, null, null, null, Instant.now()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @ComponentScan(basePackageClasses = ApiAccessPolicy.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "com\\.taxonomy\\.security\\.access\\.IntegrationOperationPermissionAdapter"))
    static class Fixture {
        @Bean AuthorizationRulesConfigurer rules() { return new AuthorizationRulesConfigurer(); }
        @Bean InMemoryUserDetailsManager users() { return new InMemoryUserDetailsManager(); }
        @Bean IntegrationStore store() { return mock(IntegrationStore.class); }
        @Bean IntegrationPublicationService publicationService() { return mock(IntegrationPublicationService.class); }
        @Bean WorkspaceResolver resolver() {
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentRepositoryContext()).thenReturn(REPOSITORY);
            return resolver;
        }
        @Bean IntegrationController controller(IntegrationService service, WorkspaceResolver resolver) {
            return new IntegrationController(service, resolver);
        }
        @Bean IntegrationService service(IntegrationStore store) {
            var repositories = mock(SystemRepositoryService.class);
            var memberships = mock(RepositoryMembershipService.class);
            var access = mock(WorkspaceAccessService.class);
            var repository = mock(SystemRepository.class);
            when(repositories.getRepository(REPOSITORY.repositoryId())).thenReturn(repository);
            when(access.canUsePrivateWorkspace(REPOSITORY)).thenReturn(true);
            when(memberships.canRead(repository, REPOSITORY.username())).thenReturn(true);
            when(memberships.canContribute(repository, REPOSITORY.username())).thenReturn(true);
            return new IntegrationService(store, null, null, null, null, null, repositories, memberships, null, access);
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, AuthorizationRulesConfigurer rules) throws Exception {
            http.authorizeHttpRequests(rules::configure).httpBasic(basic -> {});
            return http.build();
        }
    }
}
