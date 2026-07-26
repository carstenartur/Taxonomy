package com.taxonomy;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Base class for application-container verification across database backends. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractDatabaseContainerIT {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String BASIC_AUTH = "Basic "
            + Base64.getEncoder().encodeToString(
                    ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD)
                            .getBytes(StandardCharsets.UTF_8));

    protected abstract GenericContainer<?> getAppContainer();

    private String baseUrl() {
        GenericContainer<?> application = getAppContainer();
        return "http://" + application.getHost() + ":"
                + application.getMappedPort(8080);
    }

    private JsonNode getDiagnostics() throws Exception {
        HttpResponse<String> response = httpGet("/api/diagnostics");
        assertThat(response.statusCode()).isEqualTo(200);
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void containerStartsSuccessfully() {
        assertThat(getAppContainer().isRunning()).isTrue();
    }

    @Test
    @Order(2)
    void diagnosticsEndpointReturns200() throws Exception {
        assertThat(httpGet("/api/diagnostics").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(3)
    void diagnosticsResponseContainsAllFields() throws Exception {
        JsonNode diagnostics = getDiagnostics();
        assertThat(diagnostics.has("provider")).isTrue();
        assertThat(diagnostics.has("apiKeyConfigured")).isTrue();
        assertThat(diagnostics.has("totalCalls")).isTrue();
        assertThat(diagnostics.has("successfulCalls")).isTrue();
        assertThat(diagnostics.has("failedCalls")).isTrue();
        assertThat(diagnostics.has("serverTime")).isTrue();
    }

    @Test
    @Order(4)
    void diagnosticsContentTypeIsJson() throws Exception {
        HttpResponse<String> response = httpGet("/api/diagnostics");
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
    }

    @Test
    @Order(5)
    void providerIsStringAndNotEmpty() throws Exception {
        JsonNode diagnostics = getDiagnostics();
        assertThat(diagnostics.get("provider").isTextual()).isTrue();
        assertThat(diagnostics.get("provider").textValue()).isNotEmpty();
    }

    @Test
    @Order(6)
    void apiKeyNotConfiguredWithoutEnvironmentVariable() throws Exception {
        JsonNode diagnostics = getDiagnostics();
        assertThat(diagnostics.get("apiKeyConfigured").booleanValue()).isFalse();
        assertThat(diagnostics.get("apiKeyPrefix").isNull()).isTrue();
    }

    @Test
    @Order(7)
    void initialCallCountersAreZero() throws Exception {
        JsonNode diagnostics = getDiagnostics();
        assertThat(diagnostics.get("totalCalls").intValue()).isZero();
        assertThat(diagnostics.get("successfulCalls").intValue()).isZero();
        assertThat(diagnostics.get("failedCalls").intValue()).isZero();
    }

    @Test
    @Order(8)
    void serverTimeIsValidIso8601() throws Exception {
        Instant parsed = Instant.parse(getDiagnostics().get("serverTime").textValue());
        assertThat(parsed).isNotNull();
    }

    @Test
    @Order(14)
    void taxonomyEndpointWorksInContainer() throws Exception {
        HttpResponse<String> response = httpGet("/api/taxonomy");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(8);
    }

    @Test
    @Order(15)
    void searchEndpointWorksInContainer() throws Exception {
        HttpResponse<String> response = httpGet("/api/search?q=BP");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
    }

    @Test
    @Order(16)
    void aiStatusEndpointWorksInContainer() throws Exception {
        HttpResponse<String> response = httpGet("/api/ai-status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.has("available")).isTrue();
        assertThat(body.get("available").isBoolean()).isTrue();
    }

    @Test
    @Order(17)
    void homePageServesHtmlInContainer() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + "/"))
                .header("Accept", "text/html")
                .header("Authorization", BASIC_AUTH)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("text/html");
    }
}
