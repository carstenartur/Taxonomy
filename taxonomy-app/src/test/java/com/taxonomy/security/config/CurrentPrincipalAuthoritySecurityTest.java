package com.taxonomy.security.config;

import com.taxonomy.backup.IdentityBinding;
import com.taxonomy.backup.PrincipalId;
import com.taxonomy.security.keycloak.*;
import com.taxonomy.security.model.*;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.spec.SecretKeySpec;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Retained, server-created identities traverse the production chains and a real shared registry. */
class CurrentPrincipalAuthoritySecurityTest {
    private static final List<SimpleGrantedAuthority> ROLES = List.of(new SimpleGrantedAuthority("ROLE_ARCHITECT"));
    private AnnotationConfigWebApplicationContext context;
    private MockMvc http;
    private Registry database;
    private JdbcTemplate jdbc;
    private PrincipalIdentityService identities;
    private final List<AnnotationConfigWebApplicationContext> contexts = new ArrayList<>();

    @AfterEach void stop() { contexts.forEach(AnnotationConfigWebApplicationContext::close); }

    private void start(boolean keycloak, KeycloakPrincipalMode mode) {
        start(keycloak, mode, null);
    }

    private void start(boolean keycloak, KeycloakPrincipalMode mode, Registry sharedRegistry) {
        context = new AnnotationConfigWebApplicationContext();
        contexts.add(context);
        context.setServletContext(new MockServletContext());
        if (sharedRegistry != null) context.addBeanFactoryPostProcessor(beans -> beans.registerSingleton("registry", sharedRegistry));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "taxonomy.keycloak.principal-mode", mode.name())));
        if (keycloak) context.getEnvironment().setActiveProfiles("keycloak");
        context.register(RegistryConfiguration.class, keycloak ? OidcChain.class : LocalChain.class);
        context.refresh();
        database = context.getBean(Registry.class);
        jdbc = new JdbcTemplate(database);
        identities = context.getBean(PrincipalIdentityService.class);
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @ParameterizedTest @ValueSource(strings = {"account", "principal", "binding"})
    void retainedLocalSessionLosesAuthorityOnTheNextRequestAndRecoversWhenReenabled(String disabled) throws Exception {
        start(false, KeycloakPrincipalMode.LEGACY);
        var local = identities.local(1);
        var session = session(local(local));
        http.perform(post("/api/projects/p").session(session).with(csrf())).andExpect(status().isOk());
        String update = switch (disabled) {
            case "account" -> "update app_user set enabled=? where id=1";
            case "principal" -> "update app_principal set enabled=? where principal_id='" + local.id().value() + "'";
            default -> "update principal_binding set enabled=? where principal_id='" + local.id().value() + "'";
        };
        jdbc.update(update, disabled.equals("account") ? false : 0);
        http.perform(post("/api/projects/p").session(session).with(csrf())).andExpect(status().isForbidden());
        http.perform(get("/private-page").session(session)).andExpect(status().isForbidden());
        jdbc.update(update, disabled.equals("account") ? true : 1);
        http.perform(post("/api/projects/p").session(session).with(csrf())).andExpect(status().isOk());
    }

    @Test void anotherEnabledBindingCannotRescueTheDisabledLocalLogin() throws Exception {
        start(false, KeycloakPrincipalMode.LEGACY);
        var local = identities.local(1);
        addBinding(local, "https://idp.test", "another-login");
        jdbc.update("update principal_binding set enabled=0 where principal_id=? and binding_kind='LOCAL'", local.id().value().toString());
        assertThat(identities.isEnabled(local.id())).isTrue();
        http.perform(get("/api/projects/p").session(session(local(local)))).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "scope"})
    void retainedLocalSessionMustMatchItsRegisteredPrincipalAndScope(String invalid) throws Exception {
        start(false, KeycloakPrincipalMode.LEGACY);
        var principal = identities.local(1);
        var substituted = invalid.equals("unknown")
                ? new AppPrincipal(PrincipalId.create(), principal.scopeKey(), true)
                : new AppPrincipal(principal.id(), "foreign-scope", true);
        http.perform(get("/api/projects/p").session(session(local(substituted)))).andExpect(status().isForbidden());
    }

    @Test void independentApplicationContextsObserveDisablementWithoutSessionOrIdentityCaches() throws Exception {
        start(false, KeycloakPrincipalMode.LEGACY);
        var session = session(local(identities.local(1)));
        var first = http;
        var registry = database;
        start(false, KeycloakPrincipalMode.LEGACY, registry);
        first.perform(get("/api/projects/p").session(session)).andExpect(status().isOk());
        http.perform(get("/api/projects/p").session(session)).andExpect(status().isOk());
        jdbc.update("update app_user set enabled=false where id=1");
        first.perform(get("/api/projects/p").session(session)).andExpect(status().isForbidden());
        http.perform(get("/api/projects/p").session(session)).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings = {"principal", "binding"})
    void retainedOidcSessionUsesItsExactCurrentBinding(String disabled) throws Exception {
        start(true, KeycloakPrincipalMode.STABLE);
        var principal = identities.oidc("https://idp.test", "subject-1");
        addBinding(principal, "https://other.test", "another-login");
        var session = session(oidc(principal, "https://idp.test", "subject-1", true));
        http.perform(post("/api/projects/p").session(session).with(csrf())).andExpect(status().isOk());
        String sql = disabled.equals("principal")
                ? "update app_principal set enabled=? where principal_id=?"
                : "update principal_binding set enabled=? where principal_id=? and issuer='https://idp.test'";
        jdbc.update(sql, 0, principal.id().value().toString());
        if (disabled.equals("binding")) assertThat(identities.isEnabled(principal.id())).isTrue();
        http.perform(post("/api/projects/p").session(session).with(csrf())).andExpect(status().isForbidden());
        jdbc.update(sql, 1, principal.id().value().toString());
        http.perform(post("/api/projects/p").session(session).with(csrf())).andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "scope", "issuer", "subject", "unmapped", "untrusted", "conflicting"})
    void retainedStableSessionCannotSubstituteAnotherIdentity(String invalid) throws Exception {
        start(true, KeycloakPrincipalMode.STABLE);
        var principal = identities.oidc("https://idp.test", "subject-1");
        Authentication authentication = switch (invalid) {
            case "unknown" -> oidc(new AppPrincipal(PrincipalId.create(), principal.scopeKey(), true), "https://idp.test", "subject-1", true);
            case "scope" -> oidc(new AppPrincipal(principal.id(), "foreign-scope", true), "https://idp.test", "subject-1", true);
            case "issuer" -> oidc(principal, "https://foreign.test", "subject-1", true);
            case "subject" -> oidc(principal, "https://idp.test", "foreign-subject", true);
            case "unmapped" -> oidc(principal, "https://idp.test", "subject-1", false);
            case "conflicting" -> {
                jdbc.update("update principal_binding set subject_id='foreign-subject' where principal_id=?", principal.id().value().toString());
                yield oidc(principal, "https://idp.test", "subject-1", true);
            }
            default -> UsernamePasswordAuthenticationToken.authenticated("unknown", null, ROLES);
        };
        http.perform(get("/api/projects/p").session(session(authentication))).andExpect(status().isForbidden());
    }

    @Test void currentAuthorityStoreFailureCannotReachTheHandlerOrDiscloseItsFailure() throws Exception {
        start(false, KeycloakPrincipalMode.LEGACY);
        var session = session(local(identities.local(1)));
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var messages = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        messages.start();
        logger.addAppender(messages);
        database.unavailable = true;
        try {
            http.perform(post("/api/projects/p").session(session).with(csrf()))
                    .andExpect(status().isForbidden()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("credential-secret"))));
            assertThat(messages.list).noneMatch(event -> event.getFormattedMessage().contains("credential-secret")
                    || (event.getThrowableProxy() != null && event.getThrowableProxy().getMessage().contains("credential-secret")));
        } finally {
            logger.detachAppender(messages);
            messages.stop();
        }
        database.unavailable = false;
        http.perform(post("/api/projects/p").session(session).with(csrf())).andExpect(status().isOk());
    }

    @Test void stableSessionRetainsCsrfProtectionAndBearerAuthenticationRunsBeforeCurrentAuthority() throws Exception {
        start(true, KeycloakPrincipalMode.STABLE);
        var principal = identities.oidc("https://idp.test", "retained-subject");
        var session = session(oidc(principal, "https://idp.test", "retained-subject", true));
        http.perform(post("/api/projects/p").session(session)).andExpect(status().isForbidden());
        jdbc.update("update app_principal set enabled=0 where principal_id=?", principal.id().value().toString());
        http.perform(get("/api/projects/p").session(session).header("Authorization", "Bearer invalid-secret"))
                .andExpect(status().isUnauthorized());
        // An independently verified bearer identity replaces the disabled session, without saving a new session.
        String token = KeycloakStablePrincipalHttpTest.token();
        http.perform(get("/api/account/me").session(session).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        var result = http.perform(get("/api/account/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test void legacyOidcSessionKeepsItsExistingOwnershipCompatibility() throws Exception {
        start(true, KeycloakPrincipalMode.LEGACY);
        http.perform(post("/api/projects/p").session(session(oidc(null, "https://idp.test", "subject-1", false))).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test void freshRequestScopedAuthorityDoesNotMakeTheSameRetainedOnlySessionAuthoritative() throws Exception {
        start(false, KeycloakPrincipalMode.LEGACY);
        var authentication = UsernamePasswordAuthenticationToken.authenticated("protocol-adapter", null, ROLES);
        var session = session(authentication);
        var repository = new RequestAttributeSecurityContextRepository();
        http.perform(get("/api/projects/p").session(session).with(request -> {
            // A server adapter authenticates this request and publishes its context before authorization.
            repository.saveContext(new SecurityContextImpl(authentication), request, new MockHttpServletResponse());
            return request;
        })).andExpect(status().isOk());
        http.perform(get("/api/projects/p").session(session)).andExpect(status().isForbidden());
        http.perform(get("/api/projects/p").session(session)
                .header(RequestAttributeSecurityContextRepository.DEFAULT_REQUEST_ATTR_NAME, "true"))
                .andExpect(status().isForbidden());
    }

    private Authentication local(AppPrincipal principal) {
        var details = new PrincipalUserDetails(principal.id(), principal.scopeKey(), "unused", true, ROLES);
        return UsernamePasswordAuthenticationToken.authenticated(details, null, ROLES);
    }

    private Authentication oidc(AppPrincipal principal, String issuer, String subject, boolean stable) {
        Instant now = Instant.now();
        var token = new OidcIdToken("test-id-token", now, now.plusSeconds(300), Map.of(
                "iss", issuer, "sub", subject, "preferred_username", "alice"));
        var source = new DefaultOidcUser(ROLES, token, "preferred_username");
        var user = stable ? new PrincipalOidcUser(ROLES, source, principal) : source;
        return new OAuth2AuthenticationToken(user, ROLES, "keycloak");
    }

    private MockHttpSession session(Authentication authentication) {
        var session = new MockHttpSession(context.getServletContext());
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(authentication));
        return session;
    }

    private void addBinding(AppPrincipal principal, String issuer, String subject) {
        var binding = new IdentityBinding(IdentityBinding.Kind.OIDC, issuer, subject);
        jdbc.update("insert into principal_binding values (?, ?, 'OIDC', ?, ?, 1)", PrincipalBinding.key(binding), principal.id().value().toString(), issuer, subject);
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc @EnableWebSecurity
    static class RegistryConfiguration {
        @Bean Registry registry() { return new Registry(); }
        @Bean PrincipalIdentityService identities(Registry database) { return new PrincipalIdentityService(database); }
        @Bean AuthorizationRulesConfigurer rules() { return new AuthorizationRulesConfigurer(); }
        @Bean Probe probe() { return new Probe(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class LocalChain {
        @Bean org.springframework.security.provisioning.InMemoryUserDetailsManager users() {
            return new org.springframework.security.provisioning.InMemoryUserDetailsManager();
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, AuthorizationRulesConfigurer rules) throws Exception {
            return new SecurityConfig(rules, new PasswordChangeRequiredFilter(null, false))
                    .trackedSecurityFilterChain(http, new SessionRegistryImpl());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OidcChain {
        @Bean KeycloakPrincipalMode mode(org.springframework.core.env.Environment environment) {
            return KeycloakPrincipalMode.valueOf(environment.getProperty("taxonomy.keycloak.principal-mode"));
        }
        @Bean ClientRegistrationRepository clients() { return KeycloakStablePrincipalHttpTest.Config.registrations(); }
        @Bean JwtDecoder decoder() { return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KeycloakStablePrincipalHttpTest.KEY, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build(); }
        @Bean KeycloakJwtAuthConverter converter(PrincipalIdentityService identities, KeycloakPrincipalMode mode) { return new KeycloakJwtAuthConverter(identities, mode); }
        @Bean KeycloakOidcUserService oidc(PrincipalIdentityService identities, KeycloakPrincipalMode mode) { return new KeycloakOidcUserService(identities, mode); }
        @Bean SecurityFilterChain chain(HttpSecurity http, AuthorizationRulesConfigurer rules, KeycloakJwtAuthConverter converter, KeycloakOidcUserService oidc) throws Exception {
            return new KeycloakSecurityConfig(rules, converter, oidc, new KeycloakLogoutHandler(), new KeycloakAuthenticationEntryPoint())
                    .trackedSecurityFilterChain(http, new SessionRegistryImpl());
        }
    }

    @RestController static class Probe {
        @RequestMapping({"/api/projects/p", "/private-page", "/api/account/me"}) String ok() { return "ok"; }
    }

    static final class Registry extends AbstractDataSource {
        final JDBCDataSource delegate = new JDBCDataSource();
        volatile boolean unavailable;
        Registry() {
            delegate.setUrl("jdbc:hsqldb:mem:current-authority-" + UUID.randomUUID()); delegate.setUser("sa");
            var jdbc = new JdbcTemplate(delegate);
            jdbc.execute("create table app_user(id bigint primary key, username varchar(255), enabled boolean)");
            jdbc.update("insert into app_user values (1, 'alice', true)");
            PrincipalSchemaMigration.migrate(delegate);
        }
        @Override public Connection getConnection() throws SQLException {
            if (unavailable) throw new SQLException("credential-secret must not be exposed");
            return delegate.getConnection();
        }
        @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
    }
}
