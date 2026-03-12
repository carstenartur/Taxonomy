package com.taxonomy;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base class for container integration tests that run the app Docker image
 * against different databases. Subclasses only need to provide the
 * {@link GenericContainer} for the application (with the right DB env vars).
 * <p>
 * All test methods from the original {@code DiagnosticsContainerIT} are defined
 * here so that they are inherited and executed for every database backend.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractDatabaseContainerIT {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** Subclasses must return the running application container. */
    protected abstract GenericContainer<?> getAppContainer();

    private String baseUrl() {
        GenericContainer<?> app = getAppContainer();
        return "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    private JsonNode getDiagnostics() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/diagnostics"))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body());
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Accept", "application/json")
                .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── Phase 1: Basic diagnostics structure ─────────────────────────────────

    @Test
    @Order(1)
    void containerStartsSuccessfully() {
        assertThat(getAppContainer().isRunning()).isTrue();
    }

    @Test
    @Order(2)
    void diagnosticsEndpointReturns200() throws Exception {
        HttpResponse<String> resp = httpGet("/api/diagnostics");
        assertThat(resp.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void diagnosticsResponseContainsAllFields() throws Exception {
        JsonNode diag = getDiagnostics();
        assertThat(diag.has("provider")).isTrue();
        assertThat(diag.has("apiKeyConfigured")).isTrue();
        assertThat(diag.has("totalCalls")).isTrue();
        assertThat(diag.has("successfulCalls")).isTrue();
        assertThat(diag.has("failedCalls")).isTrue();
        assertThat(diag.has("serverTime")).isTrue();
    }

    @Test
    @Order(4)
    void diagnosticsContentTypeIsJson() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/diagnostics"))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
    }

    // ── Phase 2: Diagnostics values in container ─────────────────────────────

    @Test
    @Order(5)
    void providerIsStringAndNotEmpty() throws Exception {
        JsonNode diag = getDiagnostics();
        assertThat(diag.get("provider").isTextual()).isTrue();
        assertThat(diag.get("provider").textValue()).isNotEmpty();
    }

    @Test
    @Order(6)
    void apiKeyNotConfiguredWithoutEnvVar() throws Exception {
        JsonNode diag = getDiagnostics();
        assertThat(diag.get("apiKeyConfigured").booleanValue()).isFalse();
        assertThat(diag.get("apiKeyPrefix").isNull()).isTrue();
    }

    @Test
    @Order(7)
    void initialCallCountersAreZero() throws Exception {
        JsonNode diag = getDiagnostics();
        assertThat(diag.get("totalCalls").intValue()).isZero();
        assertThat(diag.get("successfulCalls").intValue()).isZero();
        assertThat(diag.get("failedCalls").intValue()).isZero();
    }

    @Test
    @Order(8)
    void serverTimeIsValidIso8601() throws Exception {
        JsonNode diag = getDiagnostics();
        String serverTime = diag.get("serverTime").textValue();
        Instant parsed = Instant.parse(serverTime);
        assertThat(parsed).isNotNull();
    }

    // ── Phase 5: Further API endpoints in container ──────────────────────────

    @Test
    @Order(14)
    void taxonomyEndpointWorksInContainer() throws Exception {
        HttpResponse<String> resp = httpGet("/api/taxonomy");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(8);
    }

    @Test
    @Order(15)
    void searchEndpointWorksInContainer() throws Exception {
        HttpResponse<String> resp = httpGet("/api/search?q=BP");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
    }

    @Test
    @Order(16)
    void aiStatusEndpointWorksInContainer() throws Exception {
        HttpResponse<String> resp = httpGet("/api/ai-status");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.has("available")).isTrue();
        assertThat(body.get("available").isBoolean()).isTrue();
    }

    @Test
    @Order(17)
    void homePageServesHtmlInContainer() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/"))
                .header("Accept", "text/html")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                .contains("text/html");
    }
}
