package com.taxonomy.security.keycloak;

import com.taxonomy.security.model.StablePrincipal;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class KeycloakPrincipalModeTest {
    private PrincipalIdentityService identities;
    private JdbcTemplate jdbc;
    private ApplicationContextRunner context;

    @BeforeEach void legacyOwnership() {
        var database = new JDBCDataSource();
        database.setUrl("jdbc:hsqldb:mem:legacy-login-" + UUID.randomUUID());
        database.setUser("sa");
        jdbc = new JdbcTemplate(database);
        jdbc.execute("create table user_workspace (username varchar(255), workspace_id varchar(255))");
        jdbc.update("insert into user_workspace values ('alice', 'existing-private-workspace')");
        PrincipalSchemaMigration.migrate(database);
        identities = new PrincipalIdentityService(database);
        context = new ApplicationContextRunner()
                .withInitializer(c -> c.getEnvironment().setActiveProfiles("keycloak"))
                .withUserConfiguration(LoginConfiguration.class)
                .withBean(PrincipalIdentityService.class, () -> identities);
    }

    @Test void defaultBrowserAndBearerLoginKeepTheExistingOwnershipScopeWithoutBackupAuthority() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            var bearer = c.getBean(KeycloakJwtAuthConverter.class).convert(jwt());
            var browser = c.getBean(KeycloakOidcUserService.class).loadUser(browserRequest());
            assertThat(bearer.getName()).isEqualTo("alice");
            assertThat(browser.getName()).isEqualTo("alice");
            assertThat(jdbc.queryForList("select workspace_id from user_workspace where username=?",
                    String.class, bearer.getName())).containsExactly("existing-private-workspace");
            assertThat(bearer).isNotInstanceOf(StablePrincipal.class);
            assertThat(browser).isNotInstanceOf(StablePrincipal.class);
            assertThatThrownBy(() -> identities.require(bearer)).isInstanceOf(AccessDeniedException.class);
            var browserAuthentication = UsernamePasswordAuthenticationToken.authenticated(browser, "", browser.getAuthorities());
            assertThatThrownBy(() -> identities.require(browserAuthentication)).isInstanceOf(AccessDeniedException.class);
            assertThat(jdbc.queryForObject("select count(*) from principal_binding", Integer.class)).isZero();
        });
    }

    @Test void stableIdentityRequiresExplicitOptInAndDoesNotLinkTheDisplayName() {
        context.withPropertyValues("taxonomy.keycloak.principal-mode=STABLE").run(c -> {
            assertThat(c).hasNotFailed();
            var bearer = c.getBean(KeycloakJwtAuthConverter.class).convert(jwt());
            var browser = c.getBean(KeycloakOidcUserService.class).loadUser(browserRequest());
            assertThat(bearer.getName()).startsWith("principal-").isNotEqualTo("alice");
            assertThat(browser.getName()).isEqualTo(bearer.getName());
            assertThat(bearer).isInstanceOf(StablePrincipal.class);
            assertThat(browser).isInstanceOf(StablePrincipal.class);
            assertThat(jdbc.queryForObject("select enabled from app_principal where scope_key='alice'", Integer.class)).isZero();
        });
    }

    @Test void legacyBearerKeepsTheExistingSubjectFallbackWhenDisplayNameIsAbsentOrBlank() {
        context.run(c -> {
            var converter = c.getBean(KeycloakJwtAuthConverter.class);
            for (String displayName : new String[]{null, " "}) {
                var builder = Jwt.withTokenValue("verified-token").header("alg", "RS256")
                        .issuer("https://idp.example").subject("existing-account-id");
                if (displayName != null) builder.claim("preferred_username", displayName);
                assertThat(converter.convert(builder.build()).getName()).isEqualTo("existing-account-id");
            }
        });
    }

    @Test void backupCannotStartWithLegacyKeycloakIdentities() {
        context.withPropertyValues("taxonomy.backup.enabled=true").run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("taxonomy.backup.enabled=true", "taxonomy.keycloak.principal-mode=LEGACY")
                .run(c -> assertThat(c).hasFailed());
        context.withPropertyValues("taxonomy.backup.enabled=true", "taxonomy.keycloak.principal-mode=STABLE")
                .run(c -> assertThat(c).hasNotFailed());
    }

    @Test void unknownIdentityModeCannotSilentlyFallBackToLegacy() {
        context.withPropertyValues("taxonomy.keycloak.principal-mode=STABL")
                .run(c -> assertThat(c).hasFailed());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("verified-token").header("alg", "RS256").issuer("https://idp.example")
                .subject("existing-account-id").claim("preferred_username", "alice").build();
    }

    private static OidcUserRequest browserRequest() {
        var registration = ClientRegistration.withRegistrationId("test").clientId("taxonomy")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("https://app.example/login")
                .authorizationUri("https://idp.example/auth").tokenUri("https://idp.example/token")
                .jwkSetUri("https://idp.example/keys").issuerUri("https://idp.example")
                .scope("openid").userNameAttributeName("preferred_username").build();
        Instant now = Instant.now();
        var token = new OidcIdToken("verified-token", now, now.plusSeconds(300),
                Map.of("iss", "https://idp.example", "sub", "existing-account-id", "preferred_username", "alice"));
        return new OidcUserRequest(registration,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "access", now, now.plusSeconds(300)), token);
    }

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = KeycloakJwtAuthConverter.class)
    static class LoginConfiguration { }
}
