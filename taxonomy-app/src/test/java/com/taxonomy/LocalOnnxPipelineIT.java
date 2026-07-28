package com.taxonomy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end REST verification for the local ONNX pipeline. */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runOnnxTests", matches = ".*")
class LocalOnnxPipelineIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Duration SEARCH_INDEX_TIMEOUT = Duration.ofMinutes(2);
    private static final String FULL_TEXT_SEARCH_PATH =
            "/api/search?q=Business%20Processes&maxResults=20";
    private static final String BASIC_AUTH = "Basic "
            + Base64.getEncoder().encodeToString(
                    ("admin:" + ContainerTestUtils.TEST_ADMIN_PASSWORD)
                            .getBytes(StandardCharsets.UTF_8));

    @Container
    static GenericContainer<?> app = new GenericContainer<>(
            ContainerTestUtils.sharedImage())
            .withExposedPorts(8080)
            .withEnv("TAXONOMY_ADMIN_PASSWORD", ContainerTestUtils.TEST_ADMIN_PASSWORD)
            .withEnv("TAXONOMY_REQUIRE_PASSWORD_CHANGE", "false")
            .withEnv("LLM_PROVIDER", "LOCAL_ONNX")
            .withEnv("TAXONOMY_EMBEDDING_ENABLED", "true")
            .withStartupTimeout(Duration.ofSeconds(180))
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forStatusCode(200)
                    .forPort(8080));

    private static String baseUrl;

    @BeforeAll
    static void setUp() {
        baseUrl = "http://" + app.getHost() + ":" + app.getMappedPort(8080);
    }

    private HttpResponse<String> httpGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPost(
            String path, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void containerStartsSuccessfully() {
        assertThat(app.isRunning()).isTrue();
    }

    @Test
    @Order(2)
    void embeddingStatusReportsAvailable() throws Exception {
        HttpResponse<String> response = httpGet("/api/embedding/status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.has("enabled")).isTrue();
        assertThat(body.get("enabled").booleanValue()).isTrue();
    }

    @Test
    @Order(3)
    void dslExportContainsElements() throws Exception {
        HttpResponse<String> response = httpGet("/api/dsl/export");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("element").contains("meta");
    }

    @Test
    @Order(4)
    void dslCommitAndHistoryWork() throws Exception {
        String dsl = """
                element TEST-001 type Capability {
                  title: "Pipeline Test Element";
                }
                """;
        HttpResponse<String> commitResponse = httpPost(
                "/api/dsl/commit", dsl, "text/plain");
        assertThat(commitResponse.statusCode()).isEqualTo(200);
        JsonNode commitBody = MAPPER.readTree(commitResponse.body());
        assertThat(commitBody.has("commitId")).isTrue();
        assertThat(commitBody.get("commitId").textValue()).isNotEmpty();

        HttpResponse<String> historyResponse = httpGet("/api/dsl/history");
        assertThat(historyResponse.statusCode()).isEqualTo(200);
        JsonNode historyBody = MAPPER.readTree(historyResponse.body());
        assertThat(historyBody.isObject()).isTrue();
        assertThat(historyBody.has("commits")).isTrue();
        assertThat(historyBody.get("commits").isArray()).isTrue();
        assertThat(historyBody.get("commits").size()).isGreaterThan(0);
    }

    @Test
    @Order(5)
    void taxonomyEndpointReturnsData() throws Exception {
        HttpResponse<String> response = httpGet("/api/taxonomy");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
    }

    @Test
    @Order(6)
    void fullTextSearchEndpointReturnsResults() throws Exception {
        long deadline = System.nanoTime() + SEARCH_INDEX_TIMEOUT.toNanos();
        int lastStatus = -1;
        String lastBody = "";

        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = httpGet(FULL_TEXT_SEARCH_PATH);
            lastStatus = response.statusCode();
            lastBody = response.body();
            if (lastStatus == 200) {
                JsonNode body = MAPPER.readTree(lastBody);
                if (body.isArray() && !body.isEmpty()) {
                    return;
                }
            }
            Thread.sleep(500);
        }

        throw new AssertionError(
                "Full-text index did not become usable within "
                        + SEARCH_INDEX_TIMEOUT
                        + "; last status=" + lastStatus
                        + ", last body=" + lastBody);
    }

    @Test
    @Order(7)
    void graphUpstreamEndpointResponds() throws Exception {
        assertThat(httpGet("/api/graph/node/BP/upstream").statusCode())
                .isEqualTo(200);
    }

    @Test
    @Order(8)
    void aiStatusEndpointReturnsLocalOnnx() throws Exception {
        HttpResponse<String> response = httpGet("/api/ai-status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.has("available")).isTrue();
        assertThat(body.has("level")).isTrue();
        assertThat(body.get("level").textValue()).isEqualTo("LIMITED");
        assertThat(body.get("available").booleanValue()).isTrue();
        assertThat(body.get("limited").booleanValue()).isTrue();
    }
}
