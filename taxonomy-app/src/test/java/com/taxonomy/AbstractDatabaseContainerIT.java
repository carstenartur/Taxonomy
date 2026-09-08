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

    @Test
    @Order(20)
    void reviewedIntegrationMappingsCheckpointsAndCompetingWritersWorkOnTheConfiguredDatabase() throws Exception {
        var created = httpPost("/api/workspace/create", java.util.Map.of("displayName", "Integration database " + java.util.UUID.randomUUID()), null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String workspace = MAPPER.readTree(created.body()).get("workspaceId").textValue();
        String query = "?workspaceId=" + workspace;
        assertThat(httpGet("/api/architecture/editor" + query).statusCode()).isEqualTo(200);
        String connection = java.util.UUID.randomUUID().toString();
        var connectionResponse = httpPost("/api/integrations" + query, java.util.Map.of("id", connection, "name", "Database Archi contract", "connectorId", "archimate-3.1", "authority", "BIDIRECTIONAL",
                "externalScope", java.util.Map.of("systemType", "Reference Archi", "repository", "model-db")), null);
        assertThat(connectionResponse.statusCode()).as(connectionResponse.body()).isEqualTo(200);
        String endpoint = "/api/integrations/" + connection;
        JsonNode baseline = MAPPER.readTree(httpGet(endpoint + query).body()).get("current");
        String xml = "<model xmlns=\"http://www.opengroup.org/xsd/archimate/3.0/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" identifier=\"model-db\"><name>Database exchange</name>"
                + "<elements><element identifier=\"external-role\" xsi:type=\"BusinessRole\"><name>Database role</name></element></elements></model>";
        var reviews = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (int i = 0; i < 2; i++) {
            String id = java.util.UUID.randomUUID().toString();
            var request = java.util.Map.of("operationId", id, "expected", baseline, "mediaType", "application/archimate+xml", "completeScope", true);
            String boundary = "TaxonomyIntegration" + id;
            String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n" + MAPPER.writeValueAsString(request)
                    + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"model.xml\"\r\nContent-Type: application/xml\r\n\r\n" + xml + "\r\n--" + boundary + "--\r\n";
            var preview = HTTP.send(HttpRequest.newBuilder(URI.create(baseUrl() + endpoint + "/previews" + query)).header("Authorization", BASIC_AUTH)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);
            JsonNode operation = MAPPER.readTree(preview.body()); var decisions = new java.util.TreeMap<String, String>();
            operation.get("changes").forEach(change -> decisions.put(change.get("id").textValue(), "ACCEPT"));
            reviews.add(java.util.Map.of("operationId", id, "previewFingerprint", operation.get("fingerprint").textValue(), "decisions", decisions, "rationale", "Reviewed database integration"));
        }
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var futures = new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for (var review : reviews) futures.add(executor.submit(() -> { barrier.await(); return httpPost(endpoint + "/apply" + query, review, null); }));
            var responses = new java.util.ArrayList<HttpResponse<String>>();
            for (var future : futures) responses.add(future.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).as(responses.toString()).containsExactly(200, 409);
            int winner = responses.get(0).statusCode() == 200 ? 0 : 1;
            JsonNode completed = MAPPER.readTree(responses.get(winner).body());
            assertThat(completed.get("status").textValue()).isEqualTo("COMPLETED");
            assertThat(completed.get("resultCommit").textValue()).hasSize(40);
            var retry = httpPost(endpoint + "/apply" + query, reviews.get(winner), null);
            assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
            assertThat(MAPPER.readTree(retry.body()).get("resultCommit")).isEqualTo(completed.get("resultCommit"));
            JsonNode overview = MAPPER.readTree(httpGet(endpoint + query).body());
            assertThat(overview.get("checkpoint").get("operationId")).isEqualTo(completed.get("id"));
            JsonNode identities = MAPPER.readTree(httpGet(endpoint + "/identities" + query).body());
            assertThat(identities.toString()).contains("ELEMENT:external-role");
            JsonNode editor = MAPPER.readTree(httpGet("/api/architecture/editor" + query).body()).get("document");
            assertThat(editor.get("history").size()).isEqualTo(1);
            assertThat(editor.get("dsl").textValue()).contains("Database role");
        }
    }

    private HttpResponse<String> httpPost(String path, Object body, String revision) throws Exception {
        var request = HttpRequest.newBuilder().uri(URI.create(baseUrl() + path))
                .header("Accept", "application/json").header("Content-Type", "application/json")
                .header("Authorization", BASIC_AUTH).POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (revision != null) request.header("If-Match", revision);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

}
