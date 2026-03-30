package com.taxonomy;

import org.junit.jupiter.api.BeforeAll;
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
import java.util.Base64;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that validate the full LOCAL_ONNX data pipeline using the
 * embedded bge-small-en-v1.5 model. No Selenium needed — pure REST API calls.
 * <p>
 * Run with: {@code mvn verify -Dit.test=LocalOnnxPipelineIT}
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalOnnxPipelineIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** HTTP Basic auth header value for the default admin user. */
    private static final String BASIC_AUTH = "Basic " +
            Base64.getEncoder().encodeToString("admin:admin".getBytes());

    @Container
    static GenericContainer<?> app = new GenericContainer<>(
            ContainerTestUtils.sharedImage())
            .withExposedPorts(8080)
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
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPost(String path, String body, String contentType) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .header("Authorization", BASIC_AUTH)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @Order(1)
    void containerStartsSuccessfully() {
        assertThat(app.isRunning()).isTrue();
    }

    @Test
    @Order(2)
    void embeddingStatusReportsAvailable() throws Exception {
        HttpResponse<String> resp = httpGet("/api/embedding/status");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.has("enabled")).isTrue();
        assertThat(body.get("enabled").booleanValue()).isTrue();
    }

    @Test
    @Order(3)
    void dslExportContainsElements() throws Exception {
        HttpResponse<String> resp = httpGet("/api/dsl/export");
        assertThat(resp.statusCode()).isEqualTo(200);
        String dsl = resp.body();
        assertThat(dsl).contains("element");
        assertThat(dsl).contains("meta");
    }

    @Test
    @Order(4)
    void dslCommitAndHistoryWork() throws Exception {
        String dsl = """
                element TEST-001 type Capability {
                  title: "Pipeline Test Element";
                }
                """;
        HttpResponse<String> commitResp = httpPost("/api/dsl/commit", dsl, "text/plain");
        assertThat(commitResp.statusCode()).isEqualTo(200);
        JsonNode commitBody = MAPPER.readTree(commitResp.body());
        assertThat(commitBody.has("commitId")).isTrue();
        assertThat(commitBody.get("commitId").textValue()).isNotEmpty();

        HttpResponse<String> historyResp = httpGet("/api/dsl/history");
        assertThat(historyResp.statusCode()).isEqualTo(200);
        JsonNode historyBody = MAPPER.readTree(historyResp.body());
        // /api/dsl/history returns a wrapped object with a "commits" array (changed in PR #172)
        assertThat(historyBody.isObject()).isTrue();
        assertThat(historyBody.has("commits")).isTrue();
        assertThat(historyBody.get("commits").isArray()).isTrue();
        assertThat(historyBody.get("commits").size()).isGreaterThan(0);
    }

    @Test
    @Order(5)
    void taxonomyEndpointReturnsData() throws Exception {
        HttpResponse<String> resp = httpGet("/api/taxonomy");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
    }

    @Test
    @Order(6)
    void searchEndpointReturnsResults() throws Exception {
        HttpResponse<String> resp = httpGet("/api/search?q=BP");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThan(0);
    }

    @Test
    @Order(7)
    void graphUpstreamEndpointResponds() throws Exception {
        HttpResponse<String> resp = httpGet("/api/graph/node/BP/upstream");
        assertThat(resp.statusCode()).isEqualTo(200);
    }

    @Test
    @Order(8)
    void aiStatusEndpointReturnsLocalOnnx() throws Exception {
        HttpResponse<String> resp = httpGet("/api/ai-status");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.has("available")).isTrue();
        assertThat(body.has("level")).isTrue();
        // In LOCAL_ONNX pipeline, embedding is enabled so level should be LIMITED
        assertThat(body.get("level").textValue()).isEqualTo("LIMITED");
        assertThat(body.get("available").booleanValue()).isTrue();
        assertThat(body.get("limited").booleanValue()).isTrue();
    }

    // ── Stufe 4: Embedding / Semantic Search in container ────────────────────
    // These tests prove that the ONNX Runtime native library (libonnxruntime.so)
    // links correctly against glibc in the eclipse-temurin:21-jre (Debian) image
    // and the HuggingFace download pipeline works end-to-end inside the container.

    @Test
    @Order(9)
    void embeddingModelIsActuallyAvailable() throws Exception {
        // Poll until the model is loaded (up to 60 seconds)
        boolean available = false;
        for (int i = 0; i < 12; i++) {
            HttpResponse<String> resp = httpGet("/api/embedding/status");
            if (resp.statusCode() == 200) {
                JsonNode body = MAPPER.readTree(resp.body());
                if (body.path("available").booleanValue()) {
                    available = true;
                    break;
                }
            }
            Thread.sleep(5_000);
        }
        assertThat(available).as("Embedding model should become available within 60s").isTrue();
    }

    @Test
    @Order(10)
    void embeddingIndexHasNodes() throws Exception {
        HttpResponse<String> resp = httpGet("/api/embedding/status");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.path("indexedNodes").intValue())
                .as("Indexed node count should be positive")
                .isGreaterThan(0);
    }

    @Test
    @Order(11)
    void semanticSearchReturnsRealResults() throws Exception {
        HttpResponse<String> resp = httpGet("/api/search/semantic?q=communications");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size())
                .as("Semantic search for 'communications' should return results")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(12)
    void hybridSearchReturnsRealResults() throws Exception {
        HttpResponse<String> resp = httpGet("/api/search/hybrid?q=voice+exchange");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size())
                .as("Hybrid search for 'voice exchange' should return results")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(13)
    void findSimilarNodesReturnsResults() throws Exception {
        HttpResponse<String> resp = httpGet("/api/search/similar/BP");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.isArray()).isTrue();
        assertThat(body.size())
                .as("Find similar for 'BP' should return results")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(14)
    void graphSearchReturnsStructuredResult() throws Exception {
        HttpResponse<String> resp = httpGet("/api/search/graph?q=communications");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.has("matchedNodes")).isTrue();
        assertThat(body.get("matchedNodes").isArray()).isTrue();
    }
}
