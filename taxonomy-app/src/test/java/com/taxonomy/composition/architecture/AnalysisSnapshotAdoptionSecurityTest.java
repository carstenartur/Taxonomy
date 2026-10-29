package com.taxonomy.composition.architecture;

import com.taxonomy.security.config.AuthorizationRulesConfigurer;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(AnalysisSnapshotAdoptionSecurityTest.Fixture.class)
class AnalysisSnapshotAdoptionSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired AnalysisSnapshotAdoptionService service;
    private MockMvc mvc;
    @BeforeEach void setup() { reset(service); mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void realFiltersRequireArchitectOrAdminAndCsrfForBothEndpoints() throws Exception {
        for (String path : java.util.List.of("/api/architecture/editor/snapshot-adoptions/preview","/api/architecture/editor/snapshot-adoptions")) {
            mvc.perform(post(path).with(user("alice").roles("USER")).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user("alice").roles("ARCHITECT"))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user("alice").roles("ADMIN")).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
            mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
            // Empty request reaches MVC validation, proving it passed the real authorization/CSRF filters.
            mvc.perform(post(path).with(user("alice").roles("ARCHITECT")).with(csrf())).andExpect(status().isBadRequest());
            mvc.perform(post(path).with(user("alice").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({AuthorizationRulesConfigurer.class,AnalysisSnapshotAdoptionController.class})
    static class Fixture {
        @Bean AnalysisSnapshotAdoptionService service() { return mock(AnalysisSnapshotAdoptionService.class); }
        @Bean WorkspaceResolver resolver() { return mock(WorkspaceResolver.class); }
        @Bean SecurityFilterChain chain(HttpSecurity http,AuthorizationRulesConfigurer rules) throws Exception {
            http.authorizeHttpRequests(rules::configure).exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,error) -> response.setStatus(401)));
            return http.build();
        }
    }
}
