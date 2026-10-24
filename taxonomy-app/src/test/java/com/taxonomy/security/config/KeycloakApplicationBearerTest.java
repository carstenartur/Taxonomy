package com.taxonomy.security.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises all servlet filters and database composition with a verified token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"taxonomy.keycloak.principal-mode=STABLE", "embedding.enabled=false", "llm.mock=true", "taxonomy.security.swagger-public=false",
        "spring.datasource.url=jdbc:hsqldb:mem:real-keycloak-bearer", "spring.jpa.hibernate.ddl-auto=create"})
@ActiveProfiles("keycloak") @DirtiesContext
class KeycloakApplicationBearerTest {
    private static final KeycloakJwtTestIssuer ISSUER = KeycloakJwtTestIssuer.start();
    @LocalServerPort int port;
    @Autowired JwtDecoder decoder;
    @Value("${spring.security.oauth2.client.registration.keycloak.client-id}")
    String applicationAudience;
    @DynamicPropertySource static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> System.getProperty("taxonomy.keycloak.test.issuer", ISSUER.origin()));
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> System.getProperty("taxonomy.keycloak.test.issuer") == null
                ? ISSUER.origin() + "/keys" : System.getProperty("taxonomy.keycloak.test.issuer") + "/protocol/openid-connect/certs");
    }
    @AfterAll static void stopIssuer() { ISSUER.close(); }
    @Test void verifiedBearerCanReadItsAccountInTheFullApplication() throws Exception {
        String bearer = token(ISSUER.origin());
        JsonNode account = account(bearer);
        assertThat(account.path("username").asText()).startsWith("principal-");
        if (System.getProperty("taxonomy.keycloak.test.issuer") != null) {
            assertThat(account(passwordToken("taxonomy-missing-claims", "taxonomy-missing-secret", "user"))
                    .path("username").asText()).isEqualTo(account.path("username").asText());
            assertThat(account(passwordToken("taxonomy-malformed-claims", "taxonomy-malformed-secret", "user"))
                    .path("username").asText()).isEqualTo(account.path("username").asText());
            assertThat(account(passwordToken("taxonomy-app", "taxonomy-test-secret", "architect"))
                    .path("username").asText()).isNotEqualTo(account.path("username").asText());
        }
    }
    @Test void verifiedBearerCanReadPrivateApiDocumentation() throws Exception {
        assertThat(request("/v3/api-docs", token(ISSUER.origin()))).isEqualTo(200);
    }
    @Test void anotherIssuerCannotReuseAValidSignature() throws Exception {
        assertThat(request("/api/account/me", token("https://another-idp.test"))).isEqualTo(401);
    }
    // These assertions use the application's auto-configured decoder, not a test decoder.
    // Omitting the resource-server audience constraint must fail the two audience negatives.
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderAcceptsTheConfiguredApplicationAudience() throws Exception {
        String bearer = audienceToken(ISSUER.origin(), List.of(applicationAudience),
                ISSUER.key(), Instant.now().plusSeconds(300));
        assertThat(decoder.decode(bearer).getAudience()).contains(applicationAudience);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderRejectsAnotherApplicationsAudience() throws Exception {
        String bearer = audienceToken(ISSUER.origin(), List.of("unrelated-application"),
                ISSUER.key(), Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(bearer))
                .as("same-issuer signed token for another audience must be rejected")
                .isInstanceOf(JwtException.class);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderRejectsMissingAudience() throws Exception {
        String bearer = audienceToken(ISSUER.origin(), null,
                ISSUER.key(), Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(bearer))
                .as("same-issuer signed token without aud must be rejected")
                .isInstanceOf(JwtException.class);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderPreservesIssuerValidation() throws Exception {
        String bearer = audienceToken("https://another-idp.test", List.of(applicationAudience),
                ISSUER.key(), Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(bearer)).isInstanceOf(JwtException.class);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderPreservesExpiryValidation() throws Exception {
        String bearer = audienceToken(ISSUER.origin(), List.of(applicationAudience),
                ISSUER.key(), Instant.now().minusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(bearer)).isInstanceOf(JwtException.class);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderPreservesSignatureValidation() throws Exception {
        RSAKey untrustedKey = new RSAKeyGenerator(2048).keyID(ISSUER.key().getKeyID()).generate();
        String bearer = audienceToken(ISSUER.origin(), List.of(applicationAudience),
                untrustedKey, Instant.now().plusSeconds(300));
        assertThatThrownBy(() -> decoder.decode(bearer)).isInstanceOf(JwtException.class);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderAcceptsApplicationWithinMultipleAudiences() throws Exception {
        String bearer = ISSUER.token(List.of("another-resource", applicationAudience));
        assertThat(decoder.decode(bearer).getAudience()).contains(applicationAudience);
        assertThat(request("/api/account/me", bearer)).isEqualTo(200);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionDecoderRejectsAuthorizedPartyWithoutAudience() throws Exception {
        String bearer = ISSUER.token(ISSUER.origin(), null, ISSUER.key(),
                Instant.now().plusSeconds(300), applicationAudience);
        assertThatThrownBy(() -> decoder.decode(bearer)).isInstanceOf(JwtException.class);
        assertThat(request("/api/account/me", bearer)).isEqualTo(401);
    }
    private String audienceToken(String issuer, List<String> audiences,
                                 RSAKey signingKey, Instant expiresAt) throws Exception {
        return ISSUER.token(issuer, audiences, signingKey, expiresAt, null);
    }
    private int request(String path, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }
    private String token(String issuer) throws Exception {
        String nativeIssuer = System.getProperty("taxonomy.keycloak.test.issuer");
        if (nativeIssuer != null && issuer.equals(ISSUER.origin())) {
            return passwordToken("taxonomy-app", "taxonomy-test-secret", "user");
        }
        return signedToken(issuer);
    }
    private JsonNode account(String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/account/me"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return JsonMapper.builder().build().readTree(response.body());
        }
    }
    private String passwordToken(String clientId, String clientSecret, String user) throws Exception {
        String nativeIssuer = System.getProperty("taxonomy.keycloak.test.issuer");
        try (var client = HttpClient.newHttpClient()) {
            String form = "grant_type=password&client_id=" + clientId + "&client_secret=" + clientSecret
                    + "&username=" + user + "&password=" + user + "&scope=openid%20profile";
            var response = client.send(HttpRequest.newBuilder(URI.create(nativeIssuer + "/protocol/openid-connect/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            String token = JsonMapper.builder().build().readTree(response.body()).get("access_token").asText();
            var claims = SignedJWT.parse(token).getJWTClaimsSet();
            assertThat(claims.getIssuer()).isEqualTo(nativeIssuer);
            assertThat(claims.getAudience()).contains(applicationAudience);
            assertThat(claims.getSubject()).as("basic client scope supplies subject").isNotBlank();
            decoder.decode(token);
            return token;
        }
    }
    private String signedToken(String issuer) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(ISSUER.key().getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject("person-1").audience(applicationAudience).issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300))).claim("preferred_username", "alice")
                        .claim("realm_access", Map.of("roles", List.of("ROLE_USER"))).build());
        jwt.sign(new RSASSASigner(ISSUER.key())); return jwt.serialize();
    }
    @TestConfiguration(proxyBeanMethods = false) static class SignedTokenConfiguration {
        @Bean ClientRegistrationRepository clients() { return KeycloakStablePrincipalHttpTest.Config.registrations(); }
    }
}
