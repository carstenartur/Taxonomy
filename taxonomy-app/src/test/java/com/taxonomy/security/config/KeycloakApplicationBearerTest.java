package com.taxonomy.security.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises all servlet filters and database composition with a verified token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"embedding.enabled=false", "llm.mock=true", "taxonomy.security.swagger-public=false",
        "spring.datasource.url=jdbc:hsqldb:mem:real-keycloak-bearer", "spring.jpa.hibernate.ddl-auto=create"})
@ActiveProfiles("keycloak") @DirtiesContext
class KeycloakApplicationBearerTest {
    private static final Issuer ISSUER = issuer();
    @LocalServerPort int port;
    @Autowired JwtDecoder decoder;
    @DynamicPropertySource static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> System.getProperty("taxonomy.keycloak.test.issuer", ISSUER.origin()));
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> System.getProperty("taxonomy.keycloak.test.issuer") == null
                ? ISSUER.origin() + "/keys" : System.getProperty("taxonomy.keycloak.test.issuer") + "/protocol/openid-connect/certs");
    }
    @AfterAll static void stopIssuer() { ISSUER.server().stop(0); }
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
            assertThat(claims.getSubject()).as("basic client scope supplies subject").isNotBlank();
            decoder.decode(token);
            return token;
        }
    }
    private String signedToken(String issuer) throws Exception {
        Instant now = Instant.now();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(ISSUER.key().getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject("person-1").issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(300))).claim("preferred_username", "alice")
                        .claim("realm_access", Map.of("roles", List.of("ROLE_USER"))).build());
        jwt.sign(new RSASSASigner(ISSUER.key())); return jwt.serialize();
    }
    private static Issuer issuer() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID("local-test").generate();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] keys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/keys", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, keys.length);
                try (var output = exchange.getResponseBody()) { output.write(keys); }
            });
            server.start(); return new Issuer(server, key, "http://127.0.0.1:" + server.getAddress().getPort());
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private record Issuer(HttpServer server, RSAKey key, String origin) { }
    @TestConfiguration(proxyBeanMethods = false) static class SignedTokenConfiguration {
        @Bean ClientRegistrationRepository clients() { return KeycloakStablePrincipalHttpTest.Config.registrations(); }
    }
}
