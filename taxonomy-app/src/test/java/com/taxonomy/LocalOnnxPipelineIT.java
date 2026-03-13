package com.taxonomy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that validate the full LOCAL_ONNX data pipeline using the
 * embedded all-MiniLM-L6-v2 model. No Selenium needed — pure REST API calls.
 * <p>
 * Opt-in: only runs when the {@code runOnnxTests} system property is set.
 * Run with: {@code mvn verify -DrunOnnxTests -Dit.test=LocalOnnxPipelineIT}
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "runOnnxTests", matches = ".*")
class LocalOnnxPipelineIT {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Container
    static GenericContainer<?> app = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withFileFromPath("app.jar", ContainerTestUtils.findApplicationJar())
                    .withDockerfileFromBuilder(builder -> builder
                            .from("eclipse-temurin:17-jre-alpine")
                            .workDir("/app")
                            .copy("app.jar", "app.jar")
                            .expose(8080)
                            .entryPoint("java", "-jar", "app.jar")
                            .build()))
            .withExposedPorts(8080)
            .withEnv("LLM_PROVIDER", "LOCAL_ONNX")
            .withEnv("TAXONOMY_EMBEDDING_ENABLED", "true")
            .withStartupTimeout(Duration.ofSeconds(180))
            .waitingFor(Wait.forHttp("/api/ai-status")
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
                .GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpPost(String path, String body, String contentType) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
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
        assertThat(historyBody.isArray()).isTrue();
        assertThat(historyBody.size()).isGreaterThan(0);
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
    }
}
