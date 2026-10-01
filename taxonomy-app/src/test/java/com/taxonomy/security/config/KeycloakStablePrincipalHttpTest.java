package com.taxonomy.security.config;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.taxonomy.security.controller.AccountContextController;
import com.taxonomy.security.keycloak.*;
import com.taxonomy.security.persistence.PrincipalSchemaMigration;
import com.taxonomy.security.service.PrincipalIdentityService;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real signed JWT, provider, security chain and identity registry; no mocked authentication. */
class KeycloakStablePrincipalHttpTest {
    static final byte[] KEY = "local-test-key-32-bytes-or-longer!!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private AnnotationConfigWebApplicationContext context;
    private MockMvc http;

    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "taxonomy.security.swagger-public", "false",
                "taxonomy.keycloak.role-claim-path", "realm_access.roles")));
        context.register(Config.class);
        context.refresh();
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void stop() { if (context != null) context.close(); }

    @Test void signedBearerReachesTheAuthenticatedAccountThroughTheProductionChain() throws Exception {
        http.perform(get("/api/account/me").header("Authorization", "Bearer " + token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username", org.hamcrest.Matchers.startsWith("principal-")))
                .andExpect(jsonPath("$.roles[0]").value("USER"));
    }
    @Test void invalidSignatureCannotCreateAnIdentityOrReachTheAccount() throws Exception {
        http.perform(get("/api/account/me").header("Authorization", "Bearer " + token() + "x"))
                .andExpect(status().isUnauthorized());
    }

    static String token() throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder().issuer("https://idp.test").subject("subject-1")
                        .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                        .claim("preferred_username", "alice")
                        .claim("realm_access", Map.of("roles", List.of("ROLE_USER"))).build());
        jwt.sign(new MACSigner(KEY)); return jwt.serialize();
    }

    @TestConfiguration(proxyBeanMethods = false) @EnableWebSecurity @EnableWebMvc
    static class Config {
        @Bean PrincipalIdentityService identities() {
            var db = new JDBCDataSource(); db.setUrl("jdbc:hsqldb:mem:bearer-http-" + UUID.randomUUID()); db.setUser("sa");
            PrincipalSchemaMigration.migrate(db); return new PrincipalIdentityService(db);
        }
        @Bean KeycloakJwtAuthConverter converter(PrincipalIdentityService identities) { return new KeycloakJwtAuthConverter(identities); }
        @Bean KeycloakOidcUserService oidc(PrincipalIdentityService identities) { return new KeycloakOidcUserService(identities); }
        @Bean AuthorizationRulesConfigurer rules() { return new AuthorizationRulesConfigurer(); }
        @Bean AccountContextController account(Environment environment) { return new AccountContextController(environment); }
        @Bean JwtDecoder decoder() { return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build(); }
        @Bean ClientRegistrationRepository clients() { return registrations(); }
        static ClientRegistrationRepository registrations() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("keycloak")
                    .clientId("test").authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("http://app.test/login/oauth2/code/keycloak").scope("openid")
                    .authorizationUri("https://idp.test/auth").tokenUri("https://idp.test/token")
                    .jwkSetUri("https://idp.test/keys").issuerUri("https://idp.test").userNameAttributeName("sub").build());
        }
        @Bean SecurityFilterChain chain(HttpSecurity http, AuthorizationRulesConfigurer rules, KeycloakJwtAuthConverter converter, KeycloakOidcUserService oidc) throws Exception {
            return new KeycloakSecurityConfig(rules, converter, oidc, new KeycloakLogoutHandler(), new KeycloakAuthenticationEntryPoint()).securityFilterChain(http);
        }
    }
}
