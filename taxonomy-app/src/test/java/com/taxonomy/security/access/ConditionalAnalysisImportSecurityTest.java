package com.taxonomy.security.access;

import com.taxonomy.portfolio.controller.PortfolioReviewedImportController;
import com.taxonomy.portfolio.controller.ProjectPortfolioController;
import com.taxonomy.portfolio.controller.AutopilotRequirementResponseAdvice;
import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioImportReviewDtos.PersistedReviewImport;
import com.taxonomy.portfolio.service.PortfolioReviewedImportService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.security.config.AuthorizationRulesConfigurer;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Conditional model execution must be authorized before either import persists data. */
class ConditionalAnalysisImportSecurityTest {
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private ProjectPortfolioService projects;
    private PortfolioReviewedImportService reviewed;
    private ProjectRequirementAnalysisService analyses;
    private CopilotAutomationService autopilot;

    @BeforeAll static void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Fixture.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterAll static void stop() { if (context != null) context.close(); }

    @BeforeEach void services() {
        projects = context.getBean(ProjectPortfolioService.class);
        reviewed = context.getBean(PortfolioReviewedImportService.class);
        analyses = context.getBean(ProjectRequirementAnalysisService.class);
        autopilot = context.getBean(CopilotAutomationService.class);
        reset(projects, reviewed, analyses, autopilot);
        clearInvocations(context.getBean(WorkspaceResolver.class));
        var requirement = mock(RequirementView.class);
        when(requirement.id()).thenReturn(7L);
        when(projects.importRequirements(eq(41L), anyList(), eq("alice"), any()))
                .thenReturn(List.of(requirement));
        when(reviewed.persist(eq(41L), anyList(), eq("alice"), any()))
                .thenReturn(new PersistedReviewImport(List.of(requirement), List.of()));
        var job = mock(AnalysisJobView.class);
        when(job.id()).thenReturn("import-job");
        when(analyses.enqueueProject(eq(41L), any(), eq("alice"), any())).thenReturn(job);
        var status = mock(AiAutomationStatus.class);
        when(status.autopilotReady()).thenReturn(true);
        when(status.runAfterRequirementSave()).thenReturn(true);
        when(autopilot.status()).thenReturn(status);
    }

    @ParameterizedTest @ValueSource(strings = {"import", "import-review"})
    void writeOnlyTokenCannotStartAnalysisOrPartiallyPersistAnImport(String route) throws Exception {
        mvc.perform(post(path(route)).with(csrf()).with(token("ARCHITECT", "architecture:write"))
                        .contentType("application/json").content(body(route, true)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(projects, reviewed, analyses);
        verify(context.getBean(WorkspaceResolver.class), never()).resolveCurrentContext();
    }

    @ParameterizedTest @ValueSource(strings = {"import", "import-review"})
    void ordinaryImportDoesNotNeedModelExecutionScope(String route) throws Exception {
        mvc.perform(post(path(route)).with(csrf()).with(token("ARCHITECT", "architecture:write"))
                        .contentType("application/json").content(body(route, false)))
                .andExpect(status().isCreated());
        verifyPersisted(route);
        verifyNoInteractions(analyses);
        verify(autopilot, never()).tryAutopilot(anyLong(), anyLong(), anyString(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"import", "import-review"})
    void bothScopesPermitImportAndAnalysis(String route) throws Exception {
        mvc.perform(post(path(route)).with(csrf()).with(token("ARCHITECT", "architecture:write", "analysis:run"))
                        .contentType("application/json").content(body(route, true)))
                .andExpect(status().is(route.equals("import-review") ? 202 : 201));
        verifyPersisted(route);
        verify(analyses).enqueueProject(eq(41L), any(), eq("alice"), any());
    }

    @ParameterizedTest @ValueSource(strings = {"import", "import-review"})
    void analysisScopeDoesNotGrantBusinessImportPermission(String route) throws Exception {
        mvc.perform(post(path(route)).with(csrf()).with(token("USER", "analysis:run"))
                        .contentType("application/json").content(body(route, true)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(projects, reviewed, analyses);
    }

    @ParameterizedTest @ValueSource(strings = {"import", "import-review"})
    void architectSessionRetainsBothRoleCapabilities(String route) throws Exception {
        mvc.perform(post(path(route)).with(csrf()).with(user("alice").roles("ARCHITECT"))
                        .contentType("application/json").content(body(route, true)))
                .andExpect(status().is(route.equals("import-review") ? 202 : 201));
        verifyPersisted(route);
        verify(analyses).enqueueProject(eq(41L), any(), eq("alice"), any());
    }

    @Test
    void configuredAutopilotCanFollowAnImportWhenBothCapabilitiesArePresent() throws Exception {
        mvc.perform(post(path("import")).with(csrf())
                        .with(token("ARCHITECT", "architecture:write", "analysis:run"))
                        .contentType("application/json").content(body("import", false)))
                .andExpect(status().isCreated());
        verifyPersisted("import");
        verify(autopilot).tryAutopilot(eq(41L), eq(7L), eq("alice"), any());
    }

    @ParameterizedTest @ValueSource(strings = {"import", "import-review"})
    void executionGrantCannotBeUsedForAnotherResolvedActor(String route) throws Exception {
        mvc.perform(post(path(route)).with(csrf())
                        .with(actorToken("bob", "ARCHITECT", "architecture:write", "analysis:run"))
                        .contentType("application/json").content(body(route, true)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(projects, reviewed, analyses);
        verify(context.getBean(WorkspaceResolver.class), never()).resolveCurrentContext();
    }

    private void verifyPersisted(String route) {
        if (route.equals("import-review")) verify(reviewed).persist(eq(41L), anyList(), eq("alice"), any());
        else verify(projects).importRequirements(eq(41L), anyList(), eq("alice"), any());
    }

    private static String path(String route) { return "/api/projects/41/requirements/" + route; }

    private static String body(String route, boolean analyze) {
        return route.equals("import-review")
                ? "{\"items\":[{\"decision\":\"NEW_REQUIREMENT\",\"requirementKey\":\"R-1\",\"title\":\"Reviewed\",\"text\":\"Requirement\"}],\"analyzeAfterImport\":" + analyze + "}"
                : "{\"requirements\":[{\"requirementKey\":\"R-1\",\"title\":\"Reviewed\",\"text\":\"Requirement\"}],\"analyzeAfterImport\":" + analyze + "}";
    }

    private static RequestPostProcessor token(String role, String... scopes) {
        return actorToken("alice", role, scopes);
    }

    private static RequestPostProcessor actorToken(String actor, String role, String... scopes) {
        var authorities = new ArrayList<GrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        for (String scope : scopes) authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        return jwt().jwt(jwt -> jwt.subject(actor)).authorities(authorities);
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity
    @ComponentScan(basePackageClasses = ArchitectureEditPermissionAdapter.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*PermissionAdapter"))
    static class Fixture {
        @Bean FactoryBean<ProjectPortfolioService> projects() { return serviceMock(ProjectPortfolioService.class); }
        @Bean FactoryBean<PortfolioReviewedImportService> reviewed() { return serviceMock(PortfolioReviewedImportService.class); }
        @Bean FactoryBean<ProjectRequirementAnalysisService> analyses() { return serviceMock(ProjectRequirementAnalysisService.class); }
        @Bean FactoryBean<CopilotAutomationService> autopilot() { return serviceMock(CopilotAutomationService.class); }
        @Bean AutopilotRequirementResponseAdvice autopilotAdvice(CopilotAutomationService autopilot,
                WorkspaceResolver resolver) { return new AutopilotRequirementResponseAdvice(autopilot, resolver); }
        @Bean WorkspaceResolver resolver() {
            var resolver = mock(WorkspaceResolver.class);
            when(resolver.resolveCurrentUsername()).thenReturn("alice");
            when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice", "copy", "draft", "repo"));
            return resolver;
        }
        @Bean ProjectPortfolioController candidates(ProjectPortfolioService projects,
                ProjectRequirementAnalysisService analyses, WorkspaceResolver resolver) {
            return new ProjectPortfolioController(projects, analyses, resolver, 10, 10000);
        }
        @Bean PortfolioReviewedImportController reviewedImports(PortfolioReviewedImportService reviewed,
                ProjectRequirementAnalysisService analyses, WorkspaceResolver resolver) {
            return new PortfolioReviewedImportController(reviewed, analyses, resolver, 10, 10000);
        }
        @Bean InMemoryUserDetailsManager users() { return new InMemoryUserDetailsManager(); }
        @Bean AuthorizationRulesConfigurer rules() { return new AuthorizationRulesConfigurer(); }
        @Bean SecurityFilterChain chain(HttpSecurity http, AuthorizationRulesConfigurer rules) throws Exception {
            http.authorizeHttpRequests(rules::configure).httpBasic(basic -> {});
            return http.build();
        }

        // Factory products are test doubles, so Spring must not inject the real
        // services' persistence/worker lifecycle dependencies into their mocks.
        private static <T> FactoryBean<T> serviceMock(Class<T> type) {
            return new FactoryBean<>() {
                private final T instance = mock(type);
                @Override public T getObject() { return instance; }
                @Override public Class<?> getObjectType() { return type; }
            };
        }
    }
}
