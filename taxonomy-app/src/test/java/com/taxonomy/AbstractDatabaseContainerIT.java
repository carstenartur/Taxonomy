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

    @Test
    @Order(18)
    void systemInformationUsesNativeDatabaseQueriesWithoutExposingConnectionDetails() throws Exception {
        HttpResponse<String> response = httpGet("/api/admin/system-information");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode snapshot = MAPPER.readTree(response.body());
        JsonNode database = snapshot.get("database");
        assertThat(database.get("versionSource").textValue()).isEqualTo("DATABASE_QUERY");
        assertThat(database.get("version").textValue()).isNotBlank();
        assertThat(database.get("product").textValue()).isNotBlank();
        assertThat(snapshot.get("runtime").get("availableProcessors").intValue()).isPositive();
        if (database.get("product").textValue().contains("HSQL")) {
            assertThat(database.get("storageSource").textValue()).isEqualTo("DATABASE_QUERY");
            assertThat(database.get("storage").textValue()).isEqualTo("IN_MEMORY");
            assertThat(database.get("lifetime").textValue()).isEqualTo("APPLICATION_PROCESS");
            assertThat(database.get("warnings").toString()).contains("IN_MEMORY_APPLICATION_PROCESS");
        } else {
            assertThat(database.get("storage").textValue()).isEqualTo("SERVER_MANAGED");
            assertThat(database.get("warnings").toString()).contains("STORAGE_DURABILITY_UNVERIFIED");
        }
        assertThat(response.body()).doesNotContain("jdbc:", ContainerTestUtils.TEST_ADMIN_PASSWORD);
    }
    @Test
    @Order(19)
    void semanticEditorJournalAndCheckpointsWorkOnTheConfiguredDatabase() throws Exception {
        var created = httpPost("/api/workspace/create", java.util.Map.of("displayName", "Editor database " + java.util.UUID.randomUUID()), null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String workspaceId = MAPPER.readTree(created.body()).get("workspaceId").textValue();
        String endpoint = "/api/architecture/editor?workspaceId=" + workspaceId;
        JsonNode initial = MAPPER.readTree(httpGet(endpoint).body()).get("document");
        JsonNode context = initial.get("context");
        String id = java.util.UUID.randomUUID().toString();
        var command = java.util.Map.of("context", context, "metadata", editorMetadata(id), "kind", "CREATE_ELEMENT",
                "type", "System", "properties", java.util.Map.of("title", "Database journal test"));
        String revision = "\"workspace-revision-" + context.get("revision").longValue() + "\"";
        var preview = httpPost("/api/architecture/editor/preview?workspaceId=" + workspaceId, command, revision);
        assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
        var accepted = httpPost("/api/architecture/editor/commands?workspaceId=" + workspaceId, command, revision);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        var replay = httpPost("/api/architecture/editor/commands?workspaceId=" + workspaceId, command, revision);
        assertThat(MAPPER.readTree(replay.body()).get("replayed").booleanValue()).isTrue();
        JsonNode current = MAPPER.readTree(httpGet(endpoint).body()).get("document");
        assertThat(current.get("history").size()).isEqualTo(1);
        assertThat(current.get("context").get("commit")).isEqualTo(context.get("commit"));
        String target = id;
        for (String kind : java.util.List.of("UNDO", "REDO")) {
            String historyId = java.util.UUID.randomUUID().toString();
            JsonNode selected = current.get("context");
            var inverse = httpPost("/api/architecture/editor/commands?workspaceId=" + workspaceId,
                    java.util.Map.of("context", selected, "metadata", editorMetadata(historyId), "kind", kind, "targetOperationId", target),
                    "\"workspace-revision-" + selected.get("revision").longValue() + "\"");
            assertThat(inverse.statusCode()).as(inverse.body()).isEqualTo(200);
            target = historyId;
            current = MAPPER.readTree(httpGet(endpoint).body()).get("document");
        }
        assertThat(current.get("history").size()).isEqualTo(3);
        assertThat(current.get("context").get("commit")).isEqualTo(context.get("commit"));
        var checkpoint = java.util.Map.of("context", current.get("context"), "metadata", editorMetadata(java.util.UUID.randomUUID().toString()));
        String expected = "\"workspace-revision-" + current.get("context").get("revision").longValue() + "\"";
        var version = httpPost("/api/architecture/editor/checkpoints?workspaceId=" + workspaceId, checkpoint, expected);
        assertThat(version.statusCode()).as(version.body()).isEqualTo(200);
        assertThat(MAPPER.readTree(version.body()).get("commitCreated").booleanValue()).isTrue();
        var retried = httpPost("/api/architecture/editor/checkpoints?workspaceId=" + workspaceId, checkpoint, expected);
        assertThat(MAPPER.readTree(retried.body()).get("replayed").booleanValue()).isTrue();
        JsonNode reloaded = MAPPER.readTree(httpGet(endpoint).body()).get("document");
        assertThat(reloaded.get("history").size()).isEqualTo(3);
        assertThat(reloaded.get("versions").size()).isEqualTo(initial.get("versions").size() + 1);
        assertThat(reloaded.get("dsl").textValue()).contains("Database journal test");
    }

    private static java.util.Map<String, String> editorMetadata(String id) {
        return java.util.Map.of("commandId", id, "correlationId", id, "causationId", id, "rationale", "Database acceptance");
    }

    private HttpResponse<String> httpPost(String path, Object body, String revision) throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .header("Authorization", BASIC_AUTH).POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (revision != null) request.header("If-Match", revision);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

}
