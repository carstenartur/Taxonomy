package com.taxonomy.security.config;

import com.taxonomy.security.service.PasswordChangeService;
import com.taxonomy.security.controller.LocalLoginController;
import com.taxonomy.shared.config.I18nConfig;
import com.taxonomy.shared.config.WebMvcConfig;
import com.taxonomy.versioning.controller.DslWorkspacePreResolutionInterceptor;
import com.taxonomy.workspace.service.ExplicitWorkspacePinValidationInterceptor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LocalLoginController.class)
@Import({LocalLoginController.class, I18nConfig.class, WebMvcConfig.class, LocalLoginFlowTest.Security.class,
        LocalLoginFlowTest.ProtectedPage.class})
class LocalLoginFlowTest {
    @Autowired WebApplicationContext context;
    @MockitoBean ExplicitWorkspacePinValidationInterceptor pinInterceptor;
    @MockitoBean DslWorkspacePreResolutionInterceptor dslInterceptor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void germanQueryRendersAnAccessibleLocalizedFormAndPersistsTheChoice() throws Exception {
        mvc.perform(get("/login").param("lang", "de"))
                .andExpect(status().isOk()).andExpect(view().name("login"))
                .andExpect(cookie().value("lang", "de"))
                .andExpect(content().string(containsString("lang=\"de\"")))
                .andExpect(content().string(containsString("Benutzername")))
                .andExpect(content().string(containsString("Passwort")))
                .andExpect(content().string(containsString("Anmelden")))
                .andExpect(content().string(containsString("for=\"username\"")))
                .andExpect(content().string(containsString("class=\"login-form\"")))
                .andExpect(content().string(containsString("method=\"post\" action=\"/login\"")))
                .andExpect(content().string(containsString("autocomplete=\"current-password\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void englishDefaultAndGermanCookieBothWorkWithoutAQueryParameter() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"login-form\"")))
                .andExpect(content().string(containsString("Sign in")));
        mvc.perform(get("/login").cookie(new Cookie("lang", "de"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Benutzername")));
    }

    @Test
    void contextRelativeFormIncludesCsrfAndDoesNotPostAtTheServerRoot() throws Exception {
        mvc.perform(get("/taxonomy/login").contextPath("/taxonomy").param("lang", "de"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/taxonomy/login\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void authenticationStillRequiresCsrfAndResumesTheSavedRequest() throws Exception {
        mvc.perform(post("/login").param("username", "qa-user").param("password", "qa-password"))
                .andExpect(status().isForbidden());
        var denied = mvc.perform(get("/qa-login-protected").accept("text/html"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"))
                .andReturn();
        var session = (MockHttpSession) denied.getRequest().getSession(false);
        mvc.perform(post("/login").session(session).with(csrf())
                        .param("username", "qa-user").param("password", "qa-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/qa-login-protected?continue"));
    }

    @Test
    void errorsAndLogoutAreLocalizedWithoutDisclosingAuthenticationDetails() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_LAST_EXCEPTION", new BadCredentialsException("private-account-detail"));
        mvc.perform(get("/login").param("lang", "de").param("error", "").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Benutzername oder Passwort ist ungültig.")))
                .andExpect(content().string(not(containsString("private-account-detail"))));
        mvc.perform(get("/login").param("lang", "de").param("logout", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sie wurden abgemeldet.")));
        var result = mvc.perform(post("/login").with(csrf()).param("username", "qa-user")
                        .param("password", "wrong-password"))
                .andExpect(redirectedUrl("/login?error")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("wrong-password");
    }

    @Test
    void logoutConfirmationPreservesAuthenticationUntilTheCsrfProtectedPost() throws Exception {
        var session = signIn("");
        var page = mvc.perform(get("/logout").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/logout\"")))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/default-ui.css")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
        mvc.perform(get("/qa-login-protected").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/qa-login-protected").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).accept("text/html").param("_csrf", formCsrf(page)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/qa-login-protected").accept("text/html"))
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void logoutConfirmationAndItsStylesRespectTheServletContextPath() throws Exception {
        var session = signIn("/taxonomy");
        var page = mvc.perform(get("/taxonomy/logout").contextPath("/taxonomy").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"/taxonomy/logout\"")))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/taxonomy/default-ui.css").contextPath("/taxonomy"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/css"));
        mvc.perform(post("/taxonomy/logout").contextPath("/taxonomy").session(session)
                        .accept("text/html").param("_csrf", formCsrf(page)))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/taxonomy/login?logout"));
        assertThat(session.isInvalid()).isTrue();
    }

    private MockHttpSession signIn(String prefix) throws Exception {
        var result = mvc.perform(post(prefix + "/login").contextPath(prefix).with(csrf())
                        .param("username", "qa-user").param("password", "qa-password"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private static String formCsrf(String html) {
        var token = java.util.regex.Pattern.compile(
                "<input\\b(?=[^>]*\\bname=\"_csrf\")(?=[^>]*\\bvalue=\"([^\"]+)\")[^>]*>")
                .matcher(html);
        assertThat(token.find()).as("actual logout confirmation supplies its CSRF token").isTrue();
        return token.group(1);
    }

    @Controller
    static class ProtectedPage {
        @GetMapping("/qa-login-protected") @ResponseBody
        String protectedPage() { return "Authenticated"; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class Security {
        @Bean
        InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(User.withUsername("qa-user")
                    .password("{noop}qa-password").roles("USER").build());
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return new SecurityConfig(new AuthorizationRulesConfigurer(),
                    new PasswordChangeRequiredFilter(mock(PasswordChangeService.class), false),
                    null, null, Optional.empty())
                    .trackedSecurityFilterChain(http, new SessionRegistryImpl());
        }
    }
}
