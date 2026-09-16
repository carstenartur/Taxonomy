package com.taxonomy.security.config;

import com.taxonomy.analysis.controller.AnalysisProgressController;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real shared authorization rules, CSRF filter and MVC controller; no default CSRF injection. */
@SpringJUnitWebConfig(AnalysisRunAuthorizationTest.Fixture.class)
class AnalysisRunAuthorizationTest {
    private static final WorkspaceContext SCOPE = new WorkspaceContext("alice", "work-a", "draft", "repo-a");
    @Autowired WebApplicationContext context;
    @Autowired AnalysisProgressRegistry registry;
    @Autowired WorkspaceResolver resolver;
    private MockMvc mvc;
    private AnalysisProgressRegistry.Handle handle;

    @BeforeEach
    void setUp() {
        reset(resolver);
        when(resolver.resolveCurrentUsername()).thenAnswer(invocation ->
                SecurityContextHolder.getContext().getAuthentication().getName());
        when(resolver.resolveCurrentContext()).thenReturn(SCOPE);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        handle = registry.open(null, "alice", SCOPE, null);
    }

    @AfterEach
    void closeRun() {
        handle.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ARCHITECT", "ADMIN"})
    void ownerCanCancelWithCsrfForEveryProductRole(String role) throws Exception {
        mvc.perform(post(cancelUrl()).with(user("alice").roles(role)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.operationId").value(handle.id()))
                .andExpect(jsonPath("$.status").value("CANCELLING"));
    }

    @Test
    void authenticatedCancellationWithoutCsrfRemainsForbidden() throws Exception {
        mvc.perform(post(cancelUrl()).with(user("alice"))).andExpect(status().isForbidden());
        assertRunning();
    }

    @Test
    void invalidCsrfRemainsForbidden() throws Exception {
        mvc.perform(post(cancelUrl()).with(user("alice")).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertRunning();
    }

    @Test
    void anonymousCancellationWithCsrfStillRequiresAuthentication() throws Exception {
        mvc.perform(post(cancelUrl()).with(csrf())).andExpect(status().isUnauthorized());
        assertRunning();
    }

    @Test
    void anotherOwnerCannotCancelEvenWithAdminRole() throws Exception {
        mvc.perform(post(cancelUrl()).with(user("bob").roles("ADMIN")).with(csrf()))
                .andExpect(status().isNotFound());
        assertRunning();
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace", "repository", "branch"})
    void anotherScopeCannotCancel(String changed) throws Exception {
        when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice",
                changed.equals("workspace") ? "work-b" : "work-a",
                changed.equals("branch") ? "other" : "draft",
                changed.equals("repository") ? "repo-b" : "repo-a"));
        mvc.perform(post(cancelUrl()).with(user("alice")).with(csrf()))
                .andExpect(status().isNotFound());
        assertRunning();
    }

    @Test
    void newSiblingWritesDoNotInheritCancellationPermission() throws Exception {
        mvc.perform(post("/api/analysis-runs/" + handle.id() + "/restart")
                        .with(user("alice").roles("ADMIN")).with(csrf()))
                .andExpect(status().isForbidden());
        assertRunning();
    }

    @Test
    void ownerStatusReadStillWorksWithoutCsrf() throws Exception {
        mvc.perform(get("/api/analysis-runs/" + handle.id()).with(user("alice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    private String cancelUrl() { return "/api/analysis-runs/" + handle.id() + "/cancel"; }
    private void assertRunning() { assertEquals("RUNNING", registry.snapshot(handle.id(), "alice", SCOPE).status()); }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({AuthorizationRulesConfigurer.class, AnalysisProgressController.class})
    static class Fixture {
        @Bean AnalysisProgressRegistry registry() { return new AnalysisProgressRegistry(new MockEnvironment()); }
        @Bean WorkspaceResolver resolver() { return mock(WorkspaceResolver.class); }
        @Bean SecurityFilterChain chain(HttpSecurity http, AuthorizationRulesConfigurer rules) throws Exception {
            http.authorizeHttpRequests(rules::configure);
            http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                    (request, response, exception) -> response.setStatus(401)));
            return http.build();
        }
    }
}
