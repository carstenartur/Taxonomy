package com.taxonomy.security.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercise real authentication filters, not a preinstalled mock SecurityContext. */
@WebMvcTest(BrowserSessionProtocolTest.Probe.class)
@Import({BrowserSessionProtocolTest.Probe.class, BrowserSessionProtocolTest.Security.class})
class BrowserSessionProtocolTest {
    @Autowired WebApplicationContext context;
    @Autowired SessionRegistry registry;
    private MockMvc mvc;

    @BeforeEach
    void clearRegistrationsAndApplyTheActualSecurityFilterChain() {
        for (Object principal : registry.getAllPrincipals()) {
            for (var session : registry.getAllSessions(principal, true)) {
                registry.removeSessionInformation(session.getSessionId());
            }
        }
        // Fail setup if the real proxy is absent; never let /login fall through
        // to a static resource handler or a probe run without authentication.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void successfulBasicAuthenticationDoesNotCreateOrRegisterABrowserSession() throws Exception {
        var result = mvc.perform(get("/session-probe").with(httpBasic("operator", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("operator"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(registry.getAllPrincipals()).isEmpty();
    }

    @Test
    void successfulBearerAuthenticationDoesNotCreateOrRegisterABrowserSession() throws Exception {
        // A decoder fixture avoids a remote identity provider. The real Bearer
        // filter and authentication provider still process the Authorization header.
        var result = mvc.perform(get("/session-probe").header("Authorization", "Bearer fixture-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("api-client"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(registry.getAllPrincipals()).isEmpty();
    }

    @Test
    void formAuthenticationRegistersAnActualBrowserSession() throws Exception {
        var result = mvc.perform(post("/login").with(csrf())
                        .param("username", "operator").param("password", "test-password"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var session = result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(registry.getSessionInformation(session.getId())).isNotNull();
        assertThat(registry.getAllPrincipals()).hasSize(1);
    }

    @RestController
    static class Probe {
        @GetMapping("/session-probe")
        Map<String, String> probe(Authentication authentication) {
            return Map.of("name", authentication.getName());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class Security {
        @Bean SessionRegistry registry() { return new SessionRegistryImpl(); }
        @Bean UserDetailsService users() {
            return new InMemoryUserDetailsManager(User.withUsername("operator")
                    .password("{noop}test-password").roles("USER").build());
        }
        @Bean JwtDecoder decoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "RS256")
                    .subject("api-client").issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60)).build();
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, SessionRegistry registry) throws Exception {
            BrowserSessionConfiguration.configure(http, registry);
            return http.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .formLogin(Customizer.withDefaults()).httpBasic(Customizer.withDefaults())
                    .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())).build();
        }
    }
}
