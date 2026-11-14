package com.taxonomy.security.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.taxonomy.security.controller.AccountContextController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises all servlet filters and database composition with a verified token. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"taxonomy.keycloak.principal-mode=STABLE", "embedding.enabled=false", "llm.mock=true", "taxonomy.security.swagger-public=false",
        "taxonomy.security.audit-logging=true", "spring.datasource.url=jdbc:hsqldb:mem:real-keycloak-bearer", "spring.jpa.hibernate.ddl-auto=create"})
@ActiveProfiles("keycloak") @DirtiesContext
class KeycloakApplicationBearerTest {
    private static final KeycloakJwtTestIssuer ISSUER = KeycloakJwtTestIssuer.start();
    @LocalServerPort int port;
    @Autowired JwtDecoder decoder;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.taxonomy.security.service.PrincipalIdentityService identities;
    @MockitoSpyBean AccountContextController accountController;
    @Value("${spring.security.oauth2.client.registration.keycloak.client-id}")
    String applicationAudience;
    @DynamicPropertySource static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> System.getProperty("taxonomy.keycloak.test.issuer", ISSUER.origin()));
        properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> System.getProperty("taxonomy.keycloak.test.issuer") == null
                ? ISSUER.origin() + "/keys" : System.getProperty("taxonomy.keycloak.test.issuer") + "/protocol/openid-connect/certs");
    }
    @AfterAll static void stopIssuer() { ISSUER.close(); }
    @AfterEach void restorePublicKeys() { ISSUER.publish(ISSUER.key()); }
    @Test void verifiedBearerCanReadItsAccountInTheFullApplication() throws Exception {
        String bearer = token(ISSUER.origin());
        JsonNode account;
        try (var audit = new AuditCapture()) {
            account = account(bearer);
            String scope = account.path("username").asText();
            assertThat(scope).startsWith("principal-");
            String id = jdbc.queryForObject("select principal_id from app_principal where scope_key = ?",
                    String.class, scope);
            assertThat(id).isNotBlank();
            assertThat(audit.events.list.stream()
                    .filter(event -> event.getLoggerName().equals(SecurityAuditListener.class.getName()))
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage))
                    .anySatisfy(message -> assertThat(message).startsWith("LOGIN_SUCCESS user=" + scope + " ip=")
                            .endsWith(" principalId=" + id).doesNotContain(bearer));
        }
        if (System.getProperty("taxonomy.keycloak.test.issuer") != null) {
            assertThat(account(passwordToken("taxonomy-missing-claims", "taxonomy-missing-secret", "user"))
                    .path("username").asText()).isEqualTo(account.path("username").asText());
            assertThat(account(passwordToken("taxonomy-malformed-claims", "taxonomy-malformed-secret", "user"))
                    .path("username").asText()).isEqualTo(account.path("username").asText());
            assertThat(account(passwordToken("taxonomy-app", "taxonomy-test-secret", "architect"))
                    .path("username").asText()).isNotEqualTo(account.path("username").asText());
        }
    }
    @Test void creatingAPersonalWorkspacePersistsItsVerifiedBearerPrincipal() throws Exception {
        String token = System.getProperty("taxonomy.keycloak.test.issuer") == null
                ? ISSUER.scopedToken(applicationAudience, List.of("ROLE_ARCHITECT"), "analysis:run")
                : passwordToken("taxonomy-app", "taxonomy-test-secret", "architect");
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/workspace/create"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"Verified personal workspace\"}"))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var workspace = JsonMapper.builder().build().readTree(response.body());
            var verified = decoder.decode(token);
            String id = jdbc.queryForObject("select principal_id from principal_binding where issuer=? and subject_id=?",
                    String.class, verified.getIssuer().toString(), verified.getSubject());
            assertThat(jdbc.queryForObject("select owner_principal_id from user_workspace where workspace_id=?",
                    String.class, workspace.path("workspaceId").asText())).isEqualTo(id);
            assertThat(workspace.has("ownerPrincipalId")).isFalse();
        }
    }

    @Test void metadataAndRenameRequireTheOriginalStableWorkspaceOwner() throws Exception {
        String token = System.getProperty("taxonomy.keycloak.test.issuer") == null
                ? ISSUER.scopedToken(applicationAudience, List.of("ROLE_ARCHITECT"), "analysis:run architecture:read")
                : passwordToken("taxonomy-app", "taxonomy-test-secret", "architect");
        var currentScope = account(token).path("username").asText();
        var originalId = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        jdbc.update("insert into app_principal(principal_id,scope_key,enabled) values(?,?,1)",originalId,"reserved-" + originalId);
        jdbc.update("""
                insert into user_workspace(workspace_id,username,owner_principal_id,display_name,created_at,current_branch,
                  archived,is_default,shared,provisioning_status,topology_mode)
                values(?,?,?,'Original private workspace',current_timestamp,'draft',false,false,false,'READY','INTERNAL_SHARED')
                """,workspaceId,currentScope,originalId);
        try (var client = HttpClient.newHttpClient()) {
            String base = "http://localhost:" + port + "/api/workspace";
            var list = client.send(HttpRequest.newBuilder(URI.create(base + "/list"))
                    .header("Authorization","Bearer " + token).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.body()).doesNotContain(workspaceId, "Original private workspace");
            var info = client.send(HttpRequest.newBuilder(URI.create(base + "/" + workspaceId + "/info"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(info.statusCode()).isEqualTo(404);
            var rename = client.send(HttpRequest.newBuilder(URI.create(base + "/" + workspaceId + "/rename"))
                    .header("Authorization","Bearer " + token).header("Content-Type","application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"New private workspace\"}"))
                    .build(),HttpResponse.BodyHandlers.discarding());
            assertThat(rename.statusCode()).isIn(400, 403, 404);
            assertThat(jdbc.queryForObject("select display_name from user_workspace where workspace_id=?",
                    String.class,workspaceId)).isEqualTo("Original private workspace");
        } finally {
            jdbc.update("delete from user_workspace where workspace_id=?", workspaceId);
            jdbc.update("delete from app_principal where principal_id=?", originalId);
        }
    }

    @Test void projectionMetadataRequiresTheOwnerOfItsPersistedWorkspace() throws Exception {
        String token = System.getProperty("taxonomy.keycloak.test.issuer") == null
                ? ISSUER.scopedToken(applicationAudience, List.of("ROLE_ARCHITECT"), "analysis:run architecture:read")
                : passwordToken("taxonomy-app", "taxonomy-test-secret", "architect");
        String scope = account(token).path("username").asText();
        var verified = decoder.decode(token);
        String principal = jdbc.queryForObject("select principal_id from principal_binding where issuer=? and subject_id=?",
                String.class, verified.getIssuer().toString(), verified.getSubject());
        String previous = UUID.randomUUID().toString();
        String previousWorkspace = UUID.randomUUID().toString();
        String ownedWorkspace = UUID.randomUUID().toString();
        String base = "http://localhost:" + port + "/api/workspace";
        String insertWorkspace = """
                insert into user_workspace (workspace_id, username, owner_principal_id, display_name, created_at,
                    current_branch, archived, is_default, shared, provisioning_status, topology_mode)
                values (?, ?, ?, ?, current_timestamp, 'draft', false, false, false, 'READY', 'INTERNAL_SHARED')
                """;
        String insertProjection = """
                insert into workspace_projection (username, workspace_id, projection_commit_id, projection_branch,
                    index_commit_id, stale, created_at)
                values (?, ?, ?, 'draft', ?, false, current_timestamp)
                """;
        jdbc.update("insert into app_principal(principal_id, scope_key, enabled) values(?,?,1)", previous, "reserved-" + previous);
        try (var client = HttpClient.newHttpClient()) {
            jdbc.update(insertWorkspace, ownedWorkspace, scope, principal, "Current projection workspace");
            jdbc.update(insertWorkspace, previousWorkspace, scope, previous, "Previous projection workspace");
            var select = HttpRequest.newBuilder(URI.create(base + "/" + ownedWorkspace + "/switch"))
                    .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
            assertThat(client.send(select, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            jdbc.update(insertProjection, scope, previousWorkspace, "previous-commit", "previous-index");
            var get = HttpRequest.newBuilder(URI.create(base + "/projection"))
                    .header("Authorization", "Bearer " + token).GET().build();
            var hidden = client.send(get, HttpResponse.BodyHandlers.ofString());
            assertThat(hidden.statusCode()).isEqualTo(200);
            var projection = JsonMapper.builder().build().readTree(hidden.body());
            assertThat(projection.path("persistedProjectionCommit").isNull()).isTrue();
            assertThat(projection.path("persistedIndexCommit").isNull()).isTrue();
            assertThat(jdbc.queryForObject("select index_commit_id from workspace_projection where workspace_id=?",
                    String.class, previousWorkspace)).isEqualTo("previous-index");

            jdbc.update("delete from workspace_projection where workspace_id=?", previousWorkspace);
            jdbc.update(insertProjection, scope, ownedWorkspace, "owned-commit", "owned-index");
            var visible = client.send(get, HttpResponse.BodyHandlers.ofString());
            assertThat(visible.statusCode()).isEqualTo(200);
            var mine = JsonMapper.builder().build().readTree(visible.body());
            assertThat(mine.path("persistedProjectionCommit").asText()).isEqualTo("owned-commit");
            assertThat(mine.path("persistedIndexCommit").asText()).isEqualTo("owned-index");
        } finally {
            jdbc.update("delete from workspace_projection where workspace_id in (?, ?)", previousWorkspace, ownedWorkspace);
            jdbc.update("delete from user_workspace where workspace_id in (?, ?)", previousWorkspace, ownedWorkspace);
            jdbc.update("delete from app_principal where principal_id=?", previous);
        }
    }

    @Test void verifiedBearerCanReadPrivateApiDocumentation() throws Exception {
        assertThat(request("/v3/api-docs", token(ISSUER.origin()))).isEqualTo(200);
    }
    @Test void anotherIssuerCannotReuseAValidSignature() throws Exception {
        assertThat(request("/api/account/me", token("https://another-idp.test"))).isEqualTo(401);
    }

    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void rejectedBearersRemainUnauthorizedAndAuditedWithoutCredentialDisclosure() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        events.start();
        logger.addAppender(events);
        try (var client = HttpClient.newHttpClient()) {
            var untrusted = new RSAKeyGenerator(2048).keyID(ISSUER.key().getKeyID()).generate();
            var rejected = List.of(
                    audienceToken(ISSUER.origin(), List.of(applicationAudience), untrusted, Instant.now().plusSeconds(300)),
                    audienceToken(ISSUER.origin(), List.of(applicationAudience), ISSUER.key(), Instant.now().minusSeconds(300)));
            for (String bearer : rejected) {
                events.list.clear();
                var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/account/me"))
                        .header("Authorization", "Bearer " + bearer).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(JsonMapper.builder().build().readTree(response.body()).path("error").asText())
                        .isEqualTo("Authentication required");
                assertThat(response.body().contains(bearer)).as("The denial must not echo the bearer credential").isFalse();
                var failures = events.list.stream().filter(event -> event.getLoggerName().equals(SecurityAuditListener.class.getName())
                        && event.getFormattedMessage().startsWith("LOGIN_FAILED ")).toList();
                assertThat(failures).as("The real rejected authentication must remain audited").hasSize(1);
                assertThat(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains(bearer)
                        || Arrays.toString(event.getArgumentArray()).contains(bearer)
                        || event.getThrowableProxy() != null && ch.qos.logback.classic.spi.ThrowableProxyUtil
                                .asString(event.getThrowableProxy()).contains(bearer)))
                        .as("Neither audit nor other logs/throwables may contain the rejected bearer").isFalse();
                assertThat(failures.getFirst().getFormattedMessage().contains("audience-proof"))
                        .as("Unverified token claims are not an authenticated audit identity").isFalse();
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"principal", "binding"})
    @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void disabledVerifiedIdentityDoesNotDiscloseBearerInProviderDiagnostics(String disabled) throws Exception {
        String bearer = audienceToken(ISSUER.origin(), List.of(applicationAudience),
                ISSUER.key(), Instant.now().plusSeconds(300));
        var principal = identities.oidc(ISSUER.origin(), "audience-proof-person");
        assertThat(account(bearer).path("username").asText()).isEqualTo(principal.scopeKey());
        String sql = disabled.equals("principal")
                ? "update app_principal set enabled=? where principal_id=?"
                : "update principal_binding set enabled=? where principal_id=? and issuer=?";
        Object[] arguments = disabled.equals("principal")
                ? new Object[] {0, principal.id().value().toString()}
                : new Object[] {0, principal.id().value().toString(), ISSUER.origin()};
        var providerLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                org.springframework.security.authentication.ProviderManager.class);
        var previousLevel = providerLogger.getLevel();
        assertThat(jdbc.update(sql, arguments)).isEqualTo(1);
        clearInvocations(accountController);
        try (var audit = new AuditCapture()) {
            providerLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            var response = accountResponse(bearer);
            assertThat(response.statusCode()).isEqualTo(401);
            var body = JsonMapper.builder().build().readTree(response.body());
            assertThat(body.path("error").asText()).isEqualTo("Authentication required");
            assertThat(body.path("status").asInt()).isEqualTo(401);
            assertThat(body.size()).isEqualTo(2);
            verifyNoInteractions(accountController);
            assertThat(audit.events.list.stream().anyMatch(event ->
                    event.getLoggerName().equals(providerLogger.getName())
                            && event.getLevel() == ch.qos.logback.classic.Level.DEBUG
                            && event.getThrowableProxy() != null
                            && event.getThrowableProxy().getClassName().equals(
                                    org.springframework.security.authentication.DisabledException.class.getName())))
                    .as("The real ProviderManager account-status diagnostic must be exercised, not suppressed").isTrue();
            audit.assertNoDisclosure(response, bearer, "audience-proof");
        } finally {
            providerLogger.setLevel(previousLevel);
            arguments[0] = 1;
            assertThat(jdbc.update(sql, arguments)).isEqualTo(1);
        }
        assertThat(account(bearer).path("username").asText()).isEqualTo(principal.scopeKey());
    }

    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionChainRefreshesRotatedKeysAndRejectsRemovedOrNeverPublishedKeys() throws Exception {
        RSAKey first = new RSAKeyGenerator(2048).keyID("rotation-first").generate();
        RSAKey second = new RSAKeyGenerator(2048).keyID("rotation-second").generate();
        RSAKey replacement = new RSAKeyGenerator(2048).keyID("rotation-replacement").generate();
        RSAKey unpublished = new RSAKeyGenerator(2048).keyID("rotation-unpublished").generate();
        String firstBearer = rotatingToken(first), secondBearer = rotatingToken(second);
        String replacementBearer = rotatingToken(replacement);
        ISSUER.publish(first);
        int before = ISSUER.requestCount();
        assertThat(account(firstBearer).path("username").asText()).startsWith("principal-");
        assertThat(ISSUER.requestCount()).as("The actual decoder must retrieve the initial public key").isGreaterThan(before);

        ISSUER.publish(first, second);
        before = ISSUER.requestCount();
        assertThat(account(secondBearer).path("username").asText()).startsWith("principal-");
        assertThat(ISSUER.requestCount()).as("A newly published kid must cause an observed JWKS refresh").isGreaterThan(before);

        ISSUER.publish(replacement);
        before = ISSUER.requestCount();
        assertThat(account(replacementBearer).path("username").asText()).startsWith("principal-");
        assertThat(ISSUER.requestCount()).as("Removal is tested only after a real successful JWKS refresh").isGreaterThan(before);
        try (var audit = new AuditCapture()) {
            for (String bearer : List.of(firstBearer, secondBearer, rotatingToken(unpublished))) {
                clearInvocations(accountController);
                var response = accountResponse(bearer);
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(JsonMapper.builder().build().readTree(response.body()).path("error").asText())
                        .isEqualTo("Authentication required");
                verifyNoInteractions(accountController);
                audit.assertNoDisclosure(response, bearer, "audience-proof");
            }
        }
        assertThat(account(replacementBearer).path("username").asText()).startsWith("principal-");
    }

    @ParameterizedTest @ValueSource(ints = {503, 200})
    @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void unknownKidFailsClosedDuringJwksFailureAndRecoversAfterPublication(int jwksStatus) throws Exception {
        // Each case uses fresh kids; no decoder/cache replacement or TTL wait is needed.
        RSAKey trusted = new RSAKeyGenerator(2048).keyID("trusted-" + jwksStatus).generate();
        RSAKey unknown = new RSAKeyGenerator(2048).keyID("unknown-" + jwksStatus).generate();
        ISSUER.publish(trusted);
        assertThat(account(rotatingToken(trusted)).path("username").asText()).startsWith("principal-");
        String bearer = rotatingToken(unknown);
        ISSUER.respond(jwksStatus, jwksStatus == 503 ? "{\"error\":\"JWKS temporarily unavailable\"}" : "not-a-jwk-set");
        int before = ISSUER.requestCount();
        clearInvocations(accountController);
        try (var audit = new AuditCapture()) {
            var response = accountResponse(bearer);
            // Authentication denial and generic infrastructure errors both fail closed.
            // This says nothing about availability of already cached valid signing keys.
            assertThat(response.statusCode()).isIn(401, 500, 503);
            assertThat(ISSUER.requestCount()).as("The unknown kid must actually reach the controlled JWKS failure").isGreaterThan(before);
            verifyNoInteractions(accountController);
            var body = JsonMapper.builder().build().readTree(response.body());
            assertThat(body.path("error").asText()).isEqualTo(switch (response.statusCode()) {
                case 401 -> "Authentication required";
                case 503 -> "Service Unavailable";
                default -> "Internal Server Error";
            });
            assertThat(body.has("username")).isFalse();
            assertThat(body.has("trace")).isFalse();
            assertThat(body.has("message")).isFalse();
            audit.assertNoDisclosure(response, bearer, "audience-proof");
        }

        ISSUER.publish(unknown);
        before = ISSUER.requestCount();
        assertThat(account(bearer).path("username").asText()).startsWith("principal-");
        assertThat(ISSUER.requestCount()).as("Recovery must use the actually restored public JWKS").isGreaterThan(before);
    }

    private String rotatingToken(RSAKey key) throws Exception {
        return audienceToken(ISSUER.origin(), List.of(applicationAudience), key, Instant.now().plusSeconds(300));
    }

    private HttpResponse<String> accountResponse(String bearer) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/account/me"))
                    .header("Authorization", "Bearer " + bearer).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static final class AuditCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();

        AuditCapture() {
            events.list = new java.util.concurrent.CopyOnWriteArrayList<>();
            events.start();
            logger.addAppender(events);
        }

        void assertNoDisclosure(HttpResponse<String> response, String... secrets) {
            for (String secret : secrets) {
                assertThat((response.headers().map().toString() + response.body()).contains(secret))
                        .as("Denied responses must not expose credentials or unverified claims").isFalse();
                assertThat(events.list.stream().anyMatch(event -> event.getFormattedMessage().contains(secret)
                        || Arrays.toString(event.getArgumentArray()).contains(secret)
                        || event.getThrowableProxy() != null && ch.qos.logback.classic.spi.ThrowableProxyUtil
                                .asString(event.getThrowableProxy()).contains(secret)))
                        .as("Logs and throwable chains must not expose credentials or unverified claims").isFalse();
            }
        }

        @Override public void close() {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void verifiedReadScopeCannotBeUsedForArchitectureWrites() throws Exception {
        String read = ISSUER.scopedToken(applicationAudience, List.of("ROLE_ARCHITECT"), "architecture:read");
        assertThat(request("/api/admin/status", read)).isEqualTo(200);
        assertThat(writeRequest(read)).isEqualTo(403);
        String write = ISSUER.scopedToken(applicationAudience, List.of("ROLE_ARCHITECT"), "architecture:write");
        // The malformed body is rejected only after real authentication and authorization succeed.
        assertThat(writeRequest(write)).isEqualTo(400);
        String wrongRole = ISSUER.scopedToken(applicationAudience, List.of("ROLE_READER"), "architecture:write");
        assertThat(writeRequest(wrongRole)).isEqualTo(403);
    }

    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void unscopedVerifiedAccountHasNoBusinessDataAccess() throws Exception {
        String unscoped = ISSUER.scopedToken(applicationAudience, List.of("ROLE_ARCHITECT"), null);
        assertThat(request("/api/account/me", unscoped)).isEqualTo(200);
        assertThat(request("/api/admin/status", unscoped)).isEqualTo(403);
    }

    @Test
    void unauthenticatedRestRequestDoesNotRedirectToInteractiveLogin() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/status"))
                    .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(401);
        }
    }

    private int writeRequest(String bearer) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/architecture/editor/commands"))
                    .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
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
    void productionChainRejectsSignedBearerWithoutExpiration() throws Exception {
        String bearer = audienceToken(ISSUER.origin(), List.of(applicationAudience), ISSUER.key(), null);
        assertThat(request("/api/account/me", bearer)).isEqualTo(401);
    }
    @Test @DisabledIfSystemProperty(named = "taxonomy.keycloak.test.issuer", matches = ".+")
    void productionChainKeepsExpirationClockSkewBounded() throws Exception {
        Instant now = Instant.now();
        String withinSkew = audienceToken(ISSUER.origin(), List.of(applicationAudience), ISSUER.key(), now.minusSeconds(15));
        String pastSkew = audienceToken(ISSUER.origin(), List.of(applicationAudience), ISSUER.key(), now.minusSeconds(90));
        assertThat(request("/api/account/me", withinSkew)).isEqualTo(200);
        assertThat(request("/api/account/me", pastSkew)).isEqualTo(401);
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
