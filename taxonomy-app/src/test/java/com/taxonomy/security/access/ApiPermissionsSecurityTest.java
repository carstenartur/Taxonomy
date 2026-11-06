package com.taxonomy.security.access;

import com.taxonomy.security.config.AuthorizationRulesConfigurer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the production request policy through the real authorization filter. */
class ApiPermissionsSecurityTest {
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;

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

    @Test
    void readerCanReadButCannotRunAnalysisOrCreateDrafts() throws Exception {
        mvc.perform(get("/api/projects/p").with(user("reader").roles("READER"))).andExpect(status().isOk());
        mvc.perform(post("/api/analyze").with(csrf()).with(user("reader").roles("READER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/analysis-drafts").with(csrf()).with(user("reader").roles("READER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workspace/provision").with(csrf()).with(user("reader").roles("READER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void readerCanUsePureValidationAndPermittedExportPosts() throws Exception {
        mvc.perform(post("/api/dsl/validate").with(csrf()).with(user("reader").roles("READER")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/export/mermaid").with(csrf()).with(user("reader").roles("READER")))
                .andExpect(status().isOk());
    }

    @Test
    void analystCanRunAnalysisButCannotEditArchitecture() throws Exception {
        mvc.perform(post("/api/analyze").with(csrf()).with(user("analyst").roles("USER")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/projects/p").with(csrf()).with(user("analyst").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorNeedsExplicitBusinessRolesToEditOrAnalyze() throws Exception {
        mvc.perform(get("/api/admin/users").with(user("operator").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(put("/api/projects/p").with(csrf()).with(user("operator").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/analyze").with(csrf()).with(user("operator").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/projects/p").with(csrf()).with(user("operator").roles("ADMIN", "ARCHITECT")))
                .andExpect(status().isOk());
    }

    @Test
    void bearerScopeLimitsAnArchitectToReadOnly() throws Exception {
        var token = jwt().authorities(new SimpleGrantedAuthority("ROLE_ARCHITECT"),
                new SimpleGrantedAuthority("SCOPE_architecture:read"));
        mvc.perform(get("/api/projects/p").with(token)).andExpect(status().isOk());
        mvc.perform(put("/api/projects/p").with(csrf()).with(token)).andExpect(status().isForbidden());
    }

    @Test
    void bearerNeedsBothScopeAndRole() throws Exception {
        mvc.perform(put("/api/projects/p").with(csrf()).with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_ARCHITECT"))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/projects/p").with(csrf()).with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_READER"), new SimpleGrantedAuthority("SCOPE_architecture:write"))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/projects/p").with(csrf()).with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_ARCHITECT"), new SimpleGrantedAuthority("SCOPE_architecture:write"))))
                .andExpect(status().isOk());
    }

    @Test
    void deletionNeedsItsOwnScopeAndUnknownWritesStayDenied() throws Exception {
        mvc.perform(delete("/api/projects/p").with(csrf()).with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_ARCHITECT"), new SimpleGrantedAuthority("SCOPE_architecture:write"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/projects/p").with(csrf()).with(jwt().authorities(
                new SimpleGrantedAuthority("ROLE_ARCHITECT"), new SimpleGrantedAuthority("SCOPE_architecture:delete"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/unclassified-write").with(csrf()).with(user("root").roles("ADMIN", "ARCHITECT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void authenticatedAccountIntrospectionDoesNotGrantBusinessDataAccess() throws Exception {
        var token = jwt().authorities(new SimpleGrantedAuthority("SCOPE_openid"));
        mvc.perform(get("/api/account/me").with(token)).andExpect(status().isOk());
        mvc.perform(get("/api/projects/p").with(token)).andExpect(status().isForbidden());
    }

    @Test
    void contextPathAndHeadUseTheSameReadPermission() throws Exception {
        var token = jwt().authorities(new SimpleGrantedAuthority("ROLE_READER"),
                new SimpleGrantedAuthority("SCOPE_architecture:read"));
        mvc.perform(head("/Taxonomy/api/projects/p").contextPath("/Taxonomy").with(token))
                .andExpect(status().isOk());
        mvc.perform(put("/Taxonomy/api/projects/p").contextPath("/Taxonomy").with(csrf()).with(token))
                .andExpect(status().isForbidden());
    }

    @Test
    void analysisGetRoutesRequireExecutionPermissionIncludingHead() throws Exception {
        for (String path : java.util.List.of("/api/analyze-stream", "/api/analyze-node")) {
            mvc.perform(get(path).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("operator").roles("ADMIN"))).andExpect(status().isForbidden());
            mvc.perform(head(path).with(user("reader").roles("READER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ARCHITECT"),
                    new SimpleGrantedAuthority("SCOPE_architecture:read")))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"),
                    new SimpleGrantedAuthority("SCOPE_analysis:run")))).andExpect(status().isOk());
        }
    }

    @Test
    void externalPublicationRequiresBothTechnicalAdministrationAndBusinessPublication() throws Exception {
        for (String path : java.util.List.of("/api/workspace/external/push", "/api/workspace/external/full-sync")) {
            mvc.perform(post(path).with(csrf()).with(user("operator").roles("ADMIN"))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf()).with(user("architect").roles("ARCHITECT"))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf()).with(jwt().authorities(
                    new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_ARCHITECT"),
                    new SimpleGrantedAuthority("SCOPE_admin:configuration")))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf()).with(jwt().authorities(
                    new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_ARCHITECT"),
                    new SimpleGrantedAuthority("SCOPE_admin:configuration"),
                    new SimpleGrantedAuthority("SCOPE_architecture:publish")))).andExpect(status().isOk());
        }
    }

    @Test
    void workspaceOperationsUseTheirBusinessCapabilityRatherThanAnAdminNamespaceFallback() throws Exception {
        mvc.perform(post("/api/workspace/create").with(csrf()).with(user("operator").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/workspace/copy").with(csrf()).with(user("operator").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workspace/create").with(csrf()).with(user("analyst").roles("USER")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workspace/compare").with(csrf()).with(user("reader").roles("READER")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workspace/unclassified").with(csrf()).with(user("root").roles("ADMIN", "ARCHITECT")))
                .andExpect(status().isForbidden());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class Fixture {
        @Bean AuthorizationRulesConfigurer rules() { return new AuthorizationRulesConfigurer(); }
        @Bean Probe probe() { return new Probe(); }
        @Bean InMemoryUserDetailsManager users() { return new InMemoryUserDetailsManager(); }
        @Bean SecurityFilterChain chain(HttpSecurity http, AuthorizationRulesConfigurer rules) throws Exception {
            http.authorizeHttpRequests(rules::configure).httpBasic(basic -> {});
            return http.build();
        }
    }

    @RestController
    static class Probe {
        @RequestMapping({"/api/projects/p", "/api/analyze", "/api/analysis-drafts", "/api/workspace/provision",
                "/api/export/mermaid", "/api/dsl/validate", "/api/admin/users", "/api/unclassified-write", "/api/account/me",
                "/api/analyze-stream", "/api/analyze-node", "/api/workspace/external/push", "/api/workspace/external/full-sync",
                "/api/workspace/create", "/api/workspace/copy", "/api/workspace/compare", "/api/workspace/unclassified"})
        String ok() { return "ok"; }
    }
}
