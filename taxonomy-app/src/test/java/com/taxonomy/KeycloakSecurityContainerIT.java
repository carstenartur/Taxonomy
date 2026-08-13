package com.taxonomy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Keycloak acceptance test for browser OIDC sessions and stateless bearer
 * clients. No security filter chain, JWT decoder or identity-provider endpoint
 * is mocked.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runKeycloakTests", matches = "true")
class KeycloakSecurityContainerIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String KEYCLOAK_ALIAS = "keycloak.test";
    private static final String KEYCLOAK_ORIGIN =
            "http://" + KEYCLOAK_ALIAS + ":8080";
    private static final String REALM = "taxonomy-test";
    private static final String ISSUER = KEYCLOAK_ORIGIN + "/realms/" + REALM;
    private static final String CLIENT_ID = "taxonomy-app";
    private static final String CLIENT_SECRET = "taxonomy-test-secret";
    private static final String GIT_PROPOSAL_REVIEW_PATH =
            "/api/architecture/proposals/1/accept";
    private static final String KEYCLOAK_IMAGE = System.getProperty(
            "keycloak.container.image", "quay.io/keycloak/keycloak:26.7.0");

    private Network network;
    private GenericContainer<?> keycloakContainer;
    private GenericContainer<?> appContainer;
    private ContainerTestUtils.BrowserSession browserSession;
    private WebDriver driver;

    private String adminToken;
    private String architectToken;
    private String userToken;
    private String unsupportedRoleToken;
    private String missingRoleClaimToken;
    private String malformedRoleClaimToken;

    @BeforeAll
    void startIdentityProviderAndApplication() throws Exception {
        network = Network.newNetwork();
        keycloakContainer = keycloakContainer();
        keycloakContainer.start();

        appContainer = keycloakApp(true, false);
        appContainer.start();

        adminToken = passwordToken(CLIENT_ID, CLIENT_SECRET, "admin", "admin");
        architectToken = passwordToken(
                CLIENT_ID, CLIENT_SECRET, "architect", "architect");
        userToken = passwordToken(CLIENT_ID, CLIENT_SECRET, "user", "user");
        unsupportedRoleToken = passwordToken(
                CLIENT_ID, CLIENT_SECRET, "unsupported", "unsupported");
        missingRoleClaimToken = passwordToken(
                "taxonomy-missing-claims", "taxonomy-missing-secret", "user", "user");
        malformedRoleClaimToken = passwordToken(
                "taxonomy-malformed-claims", "taxonomy-malformed-secret", "user", "user");

        browserSession = ContainerTestUtils.startBrowser(network);
        driver = browserSession.driver();
        driver.manage().window().setSize(new org.openqa.selenium.Dimension(1400, 900));
    }

    private GenericContainer<?> keycloakContainer() {
        return new GenericContainer<>(DockerImageName.parse(KEYCLOAK_IMAGE))
                .withNetwork(network)
                .withNetworkAliases(KEYCLOAK_ALIAS)
                .withExposedPorts(8080)
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "bootstrap-admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "bootstrap-admin-password")
                .withEnv("KC_HEALTH_ENABLED", "true")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(
                                "keycloak/taxonomy-test-realm.json"),
                        "/opt/keycloak/data/import/taxonomy-test-realm.json")
                .withCommand(
                        "start-dev",
                        "--import-realm",
                        "--http-port=8080",
                        "--hostname=" + KEYCLOAK_ORIGIN,
                        "--hostname-backchannel-dynamic=true")
                .waitingFor(Wait.forHttp(
                                "/realms/" + REALM
                                        + "/.well-known/openid-configuration")
                        .forPort(8080)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    @AfterAll
    void stopContainers() throws Exception {
        ContainerTestUtils.closeAll(
                browserSession, appContainer, keycloakContainer, network);
    }

    @Test
    @Order(1)
    void privateSwaggerAndNoLocalPasswordFallbackAreEnforced() throws Exception {
        HttpResponse<String> login = appRequest(
                "GET", "/login", null, null, null, Map.of());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("/oauth2/authorization/keycloak");
        assertThat(login.body()).doesNotContain("name=\"password\"");

        HttpResponse<String> privateDocs = appRequest(
                "GET", "/v3/api-docs", null, null, null, Map.of());
        assertThat(privateDocs.statusCode()).isIn(302, 401);

        HttpResponse<String> authenticatedDocs = appRequest(
                "GET", "/v3/api-docs", userToken, null, null, Map.of());
        assertThat(authenticatedDocs.statusCode()).isEqualTo(200);
        assertThat(authenticatedDocs.body()).contains("\"openapi\"");
    }

    @Test
    @Order(2)
    void realTokensMapPrincipalAndRolesForAllSupportedUsers() throws Exception {
        assertAccount(adminToken, "admin", Set.of("USER", "ARCHITECT", "ADMIN"));
        assertAccount(architectToken, "architect", Set.of("USER", "ARCHITECT"));
        assertAccount(userToken, "user", Set.of("USER"));

        HttpResponse<String> health = appRequest(
                "GET", "/actuator/health", adminToken, null, null, Map.of());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(health.body()).get("status").asText())
                .isEqualTo("UP");
        assertThat(health.body()).contains("keycloak");

        HttpResponse<String> discovery = hostKeycloakGet(
                "/realms/" + REALM + "/.well-known/openid-configuration");
        assertThat(discovery.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(discovery.body()).get("issuer").asText())
                .isEqualTo(ISSUER);
    }

    @Test
    @Order(3)
    void bearerAuthorizationAndCsrfExemptionMatchTheRoleModel() throws Exception {
        assertThat(appRequest(
                "GET", "/api/relations", userToken, null, null, Map.of())
                .statusCode()).isEqualTo(200);

        // The Git-authoritative review endpoint requires ARCHITECT/ADMIN. Bearer
        // clients are CSRF-exempt; missing Idempotency-Key then reaches MVC as 400.
        assertThat(jsonPost(GIT_PROPOSAL_REVIEW_PATH, userToken, "{}").statusCode())
                .isEqualTo(403);
        assertThat(jsonPost(
                GIT_PROPOSAL_REVIEW_PATH, architectToken, "{}").statusCode())
                .isEqualTo(400);
        assertThat(jsonPost(GIT_PROPOSAL_REVIEW_PATH, adminToken, "{}").statusCode())
                .isEqualTo(400);

        assertThat(appRequest(
                "GET", "/api/preferences", userToken, null, null, Map.of())
                .statusCode()).isEqualTo(403);
        assertThat(appRequest(
                "GET", "/api/preferences", architectToken, null, null, Map.of())
                .statusCode()).isEqualTo(403);
        assertThat(appRequest(
                "GET", "/api/preferences", adminToken, null, null, Map.of())
                .statusCode()).isEqualTo(200);

        assertThat(emptyMultipartPost(
                "/api/import/preview/c4", userToken).statusCode()).isEqualTo(400);
        assertThat(emptyMultipartPost(
                "/api/import/c4", userToken).statusCode()).isEqualTo(403);
        assertThat(emptyMultipartPost(
                "/api/import/c4", architectToken).statusCode()).isEqualTo(400);
        assertThat(emptyMultipartPost(
                "/api/documents/upload", userToken).statusCode()).isEqualTo(403);
        assertThat(emptyMultipartPost(
                "/api/documents/upload", architectToken).statusCode()).isEqualTo(400);
    }

    @Test
    @Order(4)
    void missingMalformedAndUnsupportedRoleClaimsFailClosed() throws Exception {
        JsonNode missingClaims = jwtClaims(missingRoleClaimToken);
        assertThat(missingClaims.has("realm_access")).isFalse();
        assertNoApplicationRoles(missingRoleClaimToken, "user");

        JsonNode malformedClaims = jwtClaims(malformedRoleClaimToken);
        assertThat(malformedClaims.get("realm_access").isTextual()).isTrue();
        assertThat(malformedClaims.get("realm_access").asText())
                .isEqualTo("not-an-object");
        assertNoApplicationRoles(malformedRoleClaimToken, "user");

        JsonNode unsupportedClaims = jwtClaims(unsupportedRoleToken);
        assertThat(unsupportedClaims.toString()).contains("ROLE_UNSUPPORTED");
        assertNoApplicationRoles(unsupportedRoleToken, "unsupported");

        HttpResponse<String> invalidJwt = appRequest(
                "GET", "/api/account/me", "not-a-jwt", null, null, Map.of());
        assertThat(invalidJwt.statusCode()).isEqualTo(401);
        assertThat(invalidJwt.body()).contains("Authentication required");
    }

    @Test
    @Order(5)
    void browserAuthorizationCodeSessionKeepsCsrfAndPerformsRpInitiatedLogout()
            throws Exception {
        loginBrowser("architect", "architect");

        JsonNode account = browserJson("/api/account/me");
        assertThat(account.get("username").asText()).isEqualTo("architect");
        assertThat(jsonStringSet(account.get("roles")))
                .containsExactlyInAnyOrder("USER", "ARCHITECT");

        String sessionCookie = browserCookieHeader();
        String csrfToken = browserMeta("_csrf", "");
        String csrfHeader = browserMeta("_csrf_header", "X-CSRF-TOKEN");
        assertThat(csrfToken).isNotBlank();

        HttpResponse<String> withoutCsrf = appRequest(
                "POST", GIT_PROPOSAL_REVIEW_PATH,
                null, "application/json", "{}",
                Map.of("Cookie", sessionCookie));
        assertThat(withoutCsrf.statusCode()).isEqualTo(403);

        HttpResponse<String> withCsrf = appRequest(
                "POST", GIT_PROPOSAL_REVIEW_PATH,
                null, "application/json", "{}",
                Map.of("Cookie", sessionCookie, csrfHeader, csrfToken));
        assertThat(withCsrf.statusCode()).isEqualTo(400);

        ((JavascriptExecutor) driver).executeScript("""
                const form = document.createElement('form');
                form.method = 'post';
                form.action = '/logout';
                const csrf = document.createElement('input');
                csrf.type = 'hidden';
                csrf.name = '_csrf';
                csrf.value = arguments[0];
                form.appendChild(csrf);
                document.body.appendChild(form);
                form.submit();
                """, csrfToken);

        new WebDriverWait(driver, Duration.ofSeconds(30))
                .until(ExpectedConditions.visibilityOfElementLocated(By.id("username")));
        assertThat(driver.getCurrentUrl()).startsWith(KEYCLOAK_ORIGIN);

        HttpResponse<String> staleSession = appRequest(
                "GET", "/api/account/me", null, null, null,
                Map.of("Cookie", sessionCookie));
        assertThat(staleSession.statusCode()).isNotEqualTo(200);
    }

    @Test
    @Order(6)
    void swaggerCanBePublicByExplicitConfiguration() throws Exception {
        GenericContainer<?> publicSwaggerApp = keycloakApp(false, true);
        try {
            publicSwaggerApp.start();
            HttpResponse<String> response = request(
                    URI.create("http://" + publicSwaggerApp.getHost() + ":"
                            + publicSwaggerApp.getMappedPort(8080)
                            + "/v3/api-docs"),
                    "GET", null, null, null, Map.of());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"openapi\"");
        } finally {
            publicSwaggerApp.stop();
        }
    }

    @Test
    @Order(7)
    void keycloakOutageMarksApplicationHealthDownWithoutLocalFallback()
            throws Exception {
        keycloakContainer.stop();

        HttpResponse<String> health = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            health = appRequest(
                    "GET", "/actuator/health", null, null, null, Map.of());
            if (health.statusCode() == 503) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "Interrupted while waiting for Keycloak outage health propagation",
                        exception);
            }
        }

        assertThat(health).isNotNull();
        assertThat(health.statusCode()).isEqualTo(503);
        assertThat(MAPPER.readTree(health.body()).get("status").asText())
                .isEqualTo("DOWN");

        HttpResponse<String> login = appRequest(
                "GET", "/login", null, null, null, Map.of());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("/oauth2/authorization/keycloak");
        assertThat(login.body()).doesNotContain("name=\"password\"");
    }

    private GenericContainer<?> keycloakApp(
            boolean assignNetworkAlias, boolean swaggerPublic) {
        GenericContainer<?> container = new GenericContainer<>(
                ContainerTestUtils.sharedImage())
                .withNetwork(network)
                .withExposedPorts(8080)
                .withEnv("ADMIN_PASSWORD", ContainerTestUtils.TEST_ADMIN_PASSWORD)
                .withEnv("TAXONOMY_ADMIN_PASSWORD",
                        ContainerTestUtils.TEST_ADMIN_PASSWORD)
                .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
                .withEnv("SPRING_PROFILES_ACTIVE", "keycloak")
                .withEnv("KEYCLOAK_ISSUER_URI", ISSUER)
                .withEnv("KEYCLOAK_JWK_SET_URI",
                        ISSUER + "/protocol/openid-connect/certs")
                .withEnv("KEYCLOAK_CLIENT_ID", CLIENT_ID)
                .withEnv("KEYCLOAK_CLIENT_SECRET", CLIENT_SECRET)
                .withEnv("TAXONOMY_SWAGGER_PUBLIC",
                        Boolean.toString(swaggerPublic))
                .withEnv("TAXONOMY_EMBEDDING_ENABLED", "false")
                .withEnv("TAXONOMY_SEARCH_DIRECTORY_TYPE", "local-heap")
                .withEnv("TAXONOMY_LAZY_INIT", "false")
                .withEnv("TAXONOMY_THYMELEAF_CACHE", "false")
                .withEnv("LLM_MOCK", "true")
                .waitingFor(Wait.forHttp("/actuator/health")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        if (assignNetworkAlias) {
            container.withNetworkAliases(ContainerTestUtils.APP_NETWORK_ALIAS);
        }
        return container;
    }

    private void assertAccount(
            String token, String username, Set<String> expectedRoles)
            throws Exception {
        HttpResponse<String> response = appRequest(
                "GET", "/api/account/me", token, null, null, Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode account = MAPPER.readTree(response.body());
        assertThat(account.get("username").asText()).isEqualTo(username);
        assertThat(jsonStringSet(account.get("roles"))).isEqualTo(expectedRoles);
    }

    private void assertNoApplicationRoles(String token, String expectedUsername)
            throws Exception {
        assertAccount(token, expectedUsername, Set.of());
        assertThat(jsonPost(GIT_PROPOSAL_REVIEW_PATH, token, "{}").statusCode())
                .isEqualTo(403);
        assertThat(appRequest(
                "GET", "/api/preferences", token, null, null, Map.of())
                .statusCode()).isEqualTo(403);
    }

    private void loginBrowser(String username, String password) {
        driver.get(ContainerTestUtils.APP_ORIGIN
                + "/oauth2/authorization/keycloak");
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));
        WebElement usernameField = wait.until(
                ExpectedConditions.visibilityOfElementLocated(By.id("username")));
        usernameField.sendKeys(username);
        driver.findElement(By.id("password")).sendKeys(password);
        driver.findElement(By.id("kc-login")).click();
        wait.until(currentDriver -> currentDriver.getCurrentUrl()
                .startsWith(ContainerTestUtils.APP_ORIGIN));
        wait.until(ExpectedConditions.presenceOfElementLocated(By.id("taxonomyTree")));
    }

    private String browserMeta(String name, String fallback) {
        return (String) ((JavascriptExecutor) driver).executeScript(
                "return document.querySelector('meta[name=\"' + arguments[0]"
                        + " + '\"]')?.content || arguments[1];",
                name,
                fallback);
    }

    private JsonNode browserJson(String path) throws Exception {
        String response = (String) ((JavascriptExecutor) driver).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch(arguments[0], {headers: {'Accept': 'application/json'}})
                  .then(result => result.text())
                  .then(done)
                  .catch(error => done(JSON.stringify({error: String(error)})));
                """, path);
        return MAPPER.readTree(response);
    }

    private String browserCookieHeader() {
        return driver.manage().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
    }

    private String passwordToken(
            String clientId, String clientSecret, String username, String password)
            throws Exception {
        String form = form(Map.of(
                "grant_type", "password",
                "client_id", clientId,
                "client_secret", clientSecret,
                "username", username,
                "password", password,
                "scope", "openid profile"));
        HttpResponse<String> response = hostKeycloakPost(
                "/realms/" + REALM + "/protocol/openid-connect/token", form);
        assertThat(response.statusCode())
                .withFailMessage(
                        "Keycloak token request failed for client '%s' and user '%s': HTTP %s: %s",
                        clientId, username, response.statusCode(), response.body())
                .isEqualTo(200);
        return MAPPER.readTree(response.body()).get("access_token").asText();
    }

    private JsonNode jwtClaims(String token) throws Exception {
        String[] parts = token.split("\\.");
        assertThat(parts).hasSizeGreaterThanOrEqualTo(2);
        return MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private HttpResponse<String> jsonPost(
            String path, String token, String json) throws Exception {
        return appRequest(
                "POST", path, token, "application/json", json, Map.of());
    }

    private HttpResponse<String> emptyMultipartPost(String path, String token)
            throws Exception {
        String boundary = "taxonomy-boundary";
        return appRequest(
                "POST", path, token,
                "multipart/form-data; boundary=" + boundary,
                "--" + boundary + "--\r\n",
                Map.of());
    }

    private HttpResponse<String> appRequest(
            String method,
            String path,
            String bearerToken,
            String contentType,
            String body,
            Map<String, String> headers) throws Exception {
        return request(
                URI.create("http://" + appContainer.getHost() + ":"
                        + appContainer.getMappedPort(8080) + path),
                method,
                bearerToken,
                contentType,
                body,
                headers);
    }

    private HttpResponse<String> hostKeycloakGet(String path) throws Exception {
        return request(
                URI.create("http://" + keycloakContainer.getHost() + ":"
                        + keycloakContainer.getMappedPort(8080) + path),
                "GET", null, null, null, Map.of());
    }

    private HttpResponse<String> hostKeycloakPost(String path, String body)
            throws Exception {
        return request(
                URI.create("http://" + keycloakContainer.getHost() + ":"
                        + keycloakContainer.getMappedPort(8080) + path),
                "POST", null, "application/x-www-form-urlencoded", body, Map.of());
    }

    private static HttpResponse<String> request(
            URI uri,
            String method,
            String bearerToken,
            String contentType,
            String body,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        headers.forEach(builder::header);
        if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        } else {
            builder.GET();
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String form(Map<String, String> values) {
        Map<String, String> ordered = new LinkedHashMap<>(values);
        return ordered.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static Set<String> jsonStringSet(JsonNode array) {
        return array == null || !array.isArray()
                ? Set.of()
                : java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
    }
}
