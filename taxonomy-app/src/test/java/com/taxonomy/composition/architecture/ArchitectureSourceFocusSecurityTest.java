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

@SpringJUnitWebConfig(ArchitectureSourceFocusSecurityTest.Fixture.class)
class ArchitectureSourceFocusSecurityTest {
    @Autowired WebApplicationContext context;
    @Autowired ArchitectureSourceFocusService service;
    private MockMvc mvc;
    @BeforeEach void setup() { reset(service); mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void realFiltersRequireAuthenticationAndCsrfForReadOnlySourceInvestigation() throws Exception {
        String path = "/api/architecture/focus/suggestions";
        mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
        for (String role : java.util.List.of("USER","ARCHITECT","ADMIN")) {
            mvc.perform(post(path).with(user("alice").roles(role))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(user("alice").roles(role)).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
            // Empty body passes the real filter chain and reaches MVC validation.
            mvc.perform(post(path).with(user("alice").roles(role)).with(csrf())).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({AuthorizationRulesConfigurer.class,ArchitectureSourceFocusController.class})
    static class Fixture {
        @Bean ArchitectureSourceFocusService service() { return mock(ArchitectureSourceFocusService.class); }
        @Bean WorkspaceResolver resolver() { return mock(WorkspaceResolver.class); }
        @Bean SecurityFilterChain chain(HttpSecurity http,AuthorizationRulesConfigurer rules) throws Exception {
            http.authorizeHttpRequests(rules::configure).exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,error) -> response.setStatus(401)));
            return http.build();
        }
    }
}
