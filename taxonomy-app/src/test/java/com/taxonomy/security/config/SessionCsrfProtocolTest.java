package com.taxonomy.security.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.taxonomy.security.keycloak.KeycloakAuthenticationEntryPoint;
import com.taxonomy.security.keycloak.KeycloakJwtAuthConverter;
import com.taxonomy.security.keycloak.KeycloakLogoutHandler;
import com.taxonomy.security.keycloak.KeycloakOidcUserService;
import com.taxonomy.security.keycloak.KeycloakPrincipalMode;
import com.taxonomy.security.webdav.WebDavApplicationCredentialFilter;
import com.taxonomy.security.webdav.WebDavApplicationCredentialService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Real form/Basic/Bearer filters and production CSRF rules, without a full application startup. */
class SessionCsrfProtocolTest {
    private static final byte[] KEY = "csrf-protocol-test-signing-key-32-bytes".getBytes(StandardCharsets.UTF_8);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc http;
    private Probe probe;

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/taxonomy"})
    void localSessionWithUnsupportedBearerCannotBypassCsrf(String prefix) throws Exception {
        start(LocalSecurity.class);
        var session = signIn(prefix);

        http.perform(post(prefix + "/api/analyze").contextPath(prefix).session(session)
                        .header("Authorization", "Bearer ignored"))
                .andExpect(status().isForbidden());
        assertThat(probe.writes.get()).isZero();

        http.perform(post(prefix + "/api/analyze").contextPath(prefix).session(session).with(csrf())
                        .header("Authorization", "Bearer ignored"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("operator"));
        assertThat(probe.writes.get()).isEqualTo(1);
    }

    @Test
    void sameUsernameBasicCannotUseAnExistingSessionAsItsCsrfExemption() throws Exception {
        start(LocalSecurity.class);
        var session = signIn("");

        // The real Basic filter may reuse an authenticated same-name principal.
        // A header is therefore not proof of a new credential authentication.
        http.perform(post("/api/analyze").session(session).with(httpBasic("operator", "wrong-password")))
                .andExpect(status().isForbidden());
        assertThat(probe.writes.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/taxonomy"})
    void statelessBasicStillAuthenticatesWritesWithoutCsrf(String prefix) throws Exception {
        start(LocalSecurity.class);

        var accepted = http.perform(post(prefix + "/api/analyze").contextPath(prefix)
                        .with(httpBasic("operator", "fixture-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("operator"))
                .andReturn();
        assertThat(accepted.getRequest().getSession(false)).isNull();

        http.perform(post(prefix + "/api/analyze").contextPath(prefix)
                        .with(httpBasic("operator", "wrong-password")))
                .andExpect(status().isUnauthorized());
        http.perform(post(prefix + "/api/analyze").contextPath(prefix)
                        .header("Authorization", "Basic invalid"))
                .andExpect(status().isUnauthorized());
        assertThat(probe.writes.get()).isEqualTo(1);
    }

    @Test
    void credentialHeaderDoesNotBypassBrowserLogoutCsrf() throws Exception {
        start(LocalSecurity.class);
        var session = signIn("");

        http.perform(post("/logout").session(session).accept("text/html")
                        .header("Authorization", "Bearer ignored"))
                .andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
        http.perform(post("/logout").session(session).accept("text/html").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void keycloakBearerStillAuthenticatesEachWriteEvenWithAnExistingSession() throws Exception {
        var session = browserSession();
        start(KeycloakSecurity.class);

        var accepted = http.perform(post("/api/analyze").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("api-client"))
                .andReturn();
        assertThat(accepted.getRequest().getSession(false)).isNull();
        http.perform(post("/api/analyze").session(session).header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("api-client"));
        http.perform(post("/api/analyze").session(session).header("Authorization", "Bearer ignored"))
                .andExpect(status().isUnauthorized());
        assertThat(probe.writes.get()).isEqualTo(2);
    }

    @Test
    void keycloakWebDavBasicCannotExemptARestoredBrowserSession() throws Exception {
        var session = browserSession();
        start(KeycloakSecurity.class);

        // Use a real form-issued session to exercise the shared session repository.
        // This is a CSRF regression, not an external OIDC-login acceptance claim.
        http.perform(put("/dav/templates/report.dotx").session(session)
                        .with(httpBasic("operator", "ordinary-password")))
                .andExpect(status().isForbidden());
        assertThat(probe.writes.get()).isZero();
        http.perform(put("/dav/templates/report.dotx").session(session).with(csrf())
                        .with(httpBasic("operator", "ordinary-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("operator"));
        assertThat(probe.writes.get()).isEqualTo(1);
    }

    private MockHttpSession browserSession() throws Exception {
        start(LocalSecurity.class);
        var session = signIn("");
        context.close();
        context = null;
        return session;
    }

    private MockHttpSession signIn(String prefix) throws Exception {
        var result = http.perform(post(prefix + "/login").contextPath(prefix).with(csrf())
                        .param("username", "operator").param("password", "fixture-password"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private void start(Class<?> security) {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(security, Probe.class);
        context.refresh();
        http = webAppContextSetup(context).apply(springSecurity()).build();
        probe = context.getBean(Probe.class);
    }

    private static String token() throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder().subject("service-1").issuer("https://idp.test")
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60)))
                        .claim("preferred_username", "api-client").claim("scope", "analysis:run")
                        .claim("realm_access", Map.of("roles", List.of("ROLE_USER"))).build());
        jwt.sign(new MACSigner(KEY));
        return jwt.serialize();
    }

    @RestController
    static class Probe {
        final AtomicInteger writes = new AtomicInteger();

        @PostMapping("/api/analyze")
        Map<String, Object> write(Authentication actor) {
            return Map.of("name", actor.getName(), "writes", writes.incrementAndGet());
        }

        @PutMapping("/dav/templates/report.dotx")
        Map<String, Object> replaceTemplate(Authentication actor) {
            return write(actor);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class LocalSecurity {
        @Bean InMemoryUserDetailsManager users() {
            return new InMemoryUserDetailsManager(User.withUsername("operator")
                    .password("{noop}fixture-password").roles("USER", "ADMIN").build());
        }

        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return new SecurityConfig(new AuthorizationRulesConfigurer(),
                    new PasswordChangeRequiredFilter(null, false))
                    .trackedSecurityFilterChain(http, new SessionRegistryImpl());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @EnableWebMvc
    static class KeycloakSecurity {
        @Bean JwtDecoder decoder() {
            return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
        }

        @Bean ClientRegistrationRepository clients() {
            return KeycloakStablePrincipalHttpTest.Config.registrations();
        }

        @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return new KeycloakSecurityConfig(new AuthorizationRulesConfigurer(),
                    new KeycloakJwtAuthConverter(null, KeycloakPrincipalMode.LEGACY),
                    new KeycloakOidcUserService(null, KeycloakPrincipalMode.LEGACY),
                    new KeycloakLogoutHandler(), new KeycloakAuthenticationEntryPoint(),
                    new WebDavApplicationCredentialFilter(mock(WebDavApplicationCredentialService.class)))
                    .securityFilterChain(http);
        }
    }
}
