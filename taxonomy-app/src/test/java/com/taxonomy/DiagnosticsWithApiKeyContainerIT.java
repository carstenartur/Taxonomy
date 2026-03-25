package com.taxonomy;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Diagnostics endpoint running inside a Docker
 * container with an API key configured via environment variable.
 * Validates API key detection, prefix masking, and diagnostics counter updates
 * after LLM call attempts.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DiagnosticsWithApiKeyContainerIT {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** HTTP Basic auth header value for the default admin user. */
    private static final String BASIC_AUTH = "Basic " +
            Base64.getEncoder().encodeToString("admin:admin".getBytes());

    @Container
    static GenericContainer<?> app = new GenericContainer<>(
            ContainerTestUtils.sharedImage())
            .withExposedPorts(8080)
            .withEnv("GEMINI_API_KEY", "test1234fake")
            .withStartupTimeout(Duration.ofSeconds(120))
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forStatusCode(200)
                    .forPort(8080));

    private String baseUrl() {
        return "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    private JsonNode getDiagnostics() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/diagnostics"))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return MAPPER.readTree(resp.body());
    }

    private void callAnalyzeNode(String parentCode, String businessText) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/api/analyze-node?parentCode=" + parentCode
                        + "&businessText=" + businessText.replace(" ", "%20")))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET().build();
        HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── Phase 3: API key configured ──────────────────────────────────────────

    @Test
    @Order(1)
    void apiKeyConfiguredWhenEnvVarSet() throws Exception {
        JsonNode diag = getDiagnostics();
        assertThat(diag.get("apiKeyConfigured").booleanValue()).isTrue();
        assertThat(diag.get("apiKeyPrefix").textValue()).isEqualTo("test****");
    }

    @Test
    @Order(2)
    void apiKeyPrefixShowsFirst4Chars() throws Exception {
        JsonNode diag = getDiagnostics();
        String prefix = diag.get("apiKeyPrefix").textValue();
        assertThat(prefix).startsWith("test");
        assertThat(prefix).endsWith("****");
    }

    // ── Phase 4: Diagnostics after LLM calls ────────────────────────────────

    @Test
    @Order(3)
    void diagnosticsCounterIncrementAfterAnalyzeCall() throws Exception {
        callAnalyzeNode("BP", "Test business requirement");
        JsonNode diag = getDiagnostics();
        assertThat(diag.get("totalCalls").intValue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(4)
    void failedCallsIncrementWithInvalidKey() throws Exception {
        callAnalyzeNode("BP", "Test business requirement");
        JsonNode diag = getDiagnostics();
        assertThat(diag.get("failedCalls").intValue()).isGreaterThanOrEqualTo(1);
        assertThat(diag.get("lastCallSuccess").booleanValue()).isFalse();
        assertThat(diag.get("lastError").textValue()).isNotEmpty();
    }

    @Test
    @Order(5)
    void lastCallTimeUpdatedAfterCall() throws Exception {
        callAnalyzeNode("BP", "Test business requirement");
        JsonNode diag = getDiagnostics();
        assertThat(diag.get("lastCallTime").isNull()).isFalse();
        String lastCallTime = diag.get("lastCallTime").textValue();
        // Should be parseable as ISO-8601 Instant
        Instant parsed = Instant.parse(lastCallTime);
        assertThat(parsed).isNotNull();
    }
}
