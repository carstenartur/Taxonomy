package com.taxonomy.build;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Installed-JAR HTTP contract checks. A SQLite download and self-read are not EA execution. */
final class SparxQeaFilePackagedChecks {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MEDIA_TYPE = "application/vnd.sparx.qea";
    private static final String MODEL = "{10000000-0000-0000-0000-000000000000}";
    private static final String A = "{30000000-0000-0000-0000-000000000001}";
    private static final String B = "{30000000-0000-0000-0000-000000000002}";
    private static final String RELATION = "{40000000-0000-0000-0000-000000000001}";
    private static final String LOCAL_TITLE = "Locally reviewed QEA component — Übernahme";

    static RetainedFiles reviewedExports(PackagedPluginSupport.Application app, Path base, Path differentBase,
                                        Path template, Path output, String pluginVersion, String pluginSha) throws Exception {
        Scope scope = workspace(app);
        var profiles = get(app, scope, "/api/integrations/profiles");
        var profile = rows(profiles).stream().filter(p -> "sparx-qea".equals(p.path("id").asText())
                && "2".equals(p.path("version").asText())).toList();
        assertThat(profile).as("The independently installed writer exposes QEA profile 2").hasSize(1);
        assertThat(strings(profile.getFirst().path("capabilities"))).contains(
                "FILE_IMPORT", "FILE_EXPORT", "BASED_FILE_EXPORT_V1", "ARCHITECTURE_MODEL", "CANONICAL_PROJECTION_V1");
        assertThat(strings(profile.getFirst().path("mediaTypes"))).contains(MEDIA_TYPE);
        assertPcsRemainsReadOnly(app, scope, profiles);

        String updateRoute = connection(app, scope, "IMPORT_COPY", MODEL);
        var imported = inbound(app, scope, updateRoute, base);
        assertThat(post(app, scope, updateRoute + "/apply", review(imported)).path("status").asText()).isEqualTo("COMPLETED");
        var importedOverview = get(app, scope, updateRoute);
        assertThat(importedOverview.path("checkpoint").path("externalVersion").asText())
                .isEqualTo(PackagedPluginSupport.sha256(base));
        renameCanonicalElement(app, scope, updateRoute);

        var before = get(app, scope, updateRoute);
        var request = exportRequest(before, "UPDATE_COPY", null);
        var updatePreview = upload(app, scope, updateRoute, request, base, 200);
        assertPreview(updatePreview, "UPDATE_COPY", base, pluginVersion, pluginSha);
        assertThat(updatePreview.path("fileExport").path("modelIdentifier").asText()).isEqualTo(MODEL);
        assertThat(upload(app, scope, updateRoute, request, base, 200)).as("An exact retry retains the reviewed identity namespace")
                .isEqualTo(updatePreview);
        var wrongMode = new LinkedHashMap<String, Object>(request); wrongMode.put("mode", "NEW_PROJECT");
        upload(app, scope, updateRoute, wrongMode, base, 409);
        upload(app, scope, updateRoute, request, differentBase, 409);
        assertThat(get(app, scope, updateRoute).path("checkpoint")).isEqualTo(before.path("checkpoint"));
        assertThat(app.download(scope.route(updateRoute + "/operations/" + updatePreview.path("id").asText() + "/file"), scope.headers()).statusCode())
                .as("Preview cannot deliver an unreviewed file").isEqualTo(409);
        Delivery updated = deliver(app, scope, updateRoute, updatePreview);
        unchangedCheckpoint(app, scope, updateRoute, before);
        var updateRead = readDelivered(app, scope, updateRoute, updated, output.resolve("update-return.qea"));
        var importedDocument = imported.path("document");
        assertThat(rows(updateRead.path("artifacts")).stream().map(a -> a.path("id").asText()).toList())
                .as("Update-copy preserves package, element and diagram GUIDs")
                .containsExactlyInAnyOrderElementsOf(rows(importedDocument.path("artifacts")).stream().map(a -> a.path("id").asText()).toList());
        assertThat(updateRead.path("placements")).as("A title-only update preserves package ownership and native diagram occurrences")
                .isEqualTo(importedDocument.path("placements"));
        assertThat(matching(updateRead.path("artifacts"), "id", A).path("title").asText()).isEqualTo(LOCAL_TITLE);
        assertThat(matching(updateRead.path("artifacts"), "id", B).path("title").asText()).isEqualTo("Same name");
        var relation = matching(updateRead.path("relations"), "id", RELATION);
        assertThat(relation.path("source").asText()).isEqualTo(A);
        assertThat(relation.path("target").asText()).isEqualTo(B);

        String newRoute = connection(app, scope, "PUBLISH_TARGET", "packaged-independent-qea");
        var newBefore = get(app, scope, newRoute);
        var newRequest = exportRequest(newBefore, "NEW_PROJECT", null);
        var newPreview = upload(app, scope, newRoute, newRequest, template, 200);
        assertPreview(newPreview, "NEW_PROJECT", template, pluginVersion, pluginSha);
        String newModel = newPreview.path("fileExport").path("modelIdentifier").asText();
        assertThat(newModel).matches("\\{[0-9A-F-]{36}\\}").isNotEqualTo(MODEL);
        assertThat(upload(app, scope, newRoute, newRequest, template, 200)).isEqualTo(newPreview);
        Delivery created = deliver(app, scope, newRoute, newPreview);
        unchangedCheckpoint(app, scope, newRoute, newBefore);
        String readRoute = connection(app, scope, "IMPORT_COPY", newModel);
        var createdRead = readDelivered(app, scope, readRoute, created, output.resolve("new-return.qea"));
        assertThat(createdRead.path("metadata").path("identifier").asText()).isEqualTo(newModel);
        var newElement = matching(createdRead.path("artifacts"), "title", LOCAL_TITLE);
        assertThat(newElement.path("id").asText()).matches("\\{[0-9A-F-]{36}\\}").isNotEqualTo(A).isNotEqualTo(B);
        assertThat(rows(createdRead.path("relations"))).isNotEmpty();
        Set<String> importedIds = rows(importedDocument.path("artifacts")).stream().map(a -> a.path("id").asText())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(rows(createdRead.path("artifacts"))).allSatisfy(a -> assertThat(a.path("id").asText())
                .as("Independent projects reserve fresh package, element and diagram GUIDs").isNotIn(importedIds));

        String retainedBase = updatePreview.path("fileExport").path("baseFileId").asText();
        var basis = matching(get(app, scope, updateRoute).path("fileBases"), "id", retainedBase);
        assertThat(basis.path("sha256").asText()).isEqualTo(PackagedPluginSupport.sha256(base));
        assertThat(basis.path("bytes").asLong()).isEqualTo(Files.size(base));
        var pending = storedBasePreview(app, scope, updateRoute, retainedBase);
        return retain(app, scope, List.of(updateRoute, newRoute), List.of(updated, created), updateRoute, pending);
    }

    static RetainedFiles resumeAfterRestart(PackagedPluginSupport.Application app, RetainedFiles retained) throws Exception {
        assertRetained(app, retained);
        var pending = get(app, retained.scope(), retained.pendingRoute() + "/operations/" + retained.pending().path("id").asText());
        assertThat(pending).as("The exact preview and file basis survive process restart").isEqualTo(retained.pending());
        var before = get(app, retained.scope(), retained.pendingRoute());
        Delivery resumed = deliver(app, retained.scope(), retained.pendingRoute(), pending);
        unchangedCheckpoint(app, retained.scope(), retained.pendingRoute(), before);
        var all = new ArrayList<>(retained.deliveries()); all.add(resumed);
        var another = storedBasePreview(app, retained.scope(), retained.pendingRoute(), pending.path("fileExport").path("baseFileId").asText());
        return retain(app, retained.scope(), new ArrayList<>(retained.overviews().keySet()), all, retained.pendingRoute(), another);
    }

    static void replacedPluginRejectsRetainedReview(PackagedPluginSupport.Application app, RetainedFiles retained) throws Exception {
        assertRetained(app, retained);
        var rejected = response(app.request("POST", retained.scope().route(retained.pendingRoute() + "/files"),
                JSON.writeValueAsString(review(retained.pending())), retained.scope().headers()), 409);
        assertThat(rejected.path("code").asText()).isEqualTo("INTEGRATION_PLUGIN_CHANGED");
        assertRetained(app, retained);
    }

    static void absentPluginRetainsFiles(PackagedPluginSupport.Application app, RetainedFiles retained) throws Exception {
        assertThat(app.request("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        assertThat(rows(get(app, retained.scope(), "/api/integrations/profiles")))
                .noneMatch(p -> "sparx-qea".equals(p.path("id").asText()));
        assertRetained(app, retained);
        var rejected = response(app.request("POST", retained.scope().route(retained.pendingRoute() + "/files"),
                JSON.writeValueAsString(review(retained.pending())), retained.scope().headers()), 409);
        assertThat(rejected.path("code").asText()).isEqualTo("PROFILE_VERSION_CHANGED");
        var request = exportRequest(get(app, retained.scope(), retained.pendingRoute()), "UPDATE_COPY",
                retained.pending().path("fileExport").path("baseFileId").asText());
        assertThat(upload(app, retained.scope(), retained.pendingRoute(), request, null, 409).path("code").asText())
                .isEqualTo("PROFILE_VERSION_CHANGED");
        assertRetained(app, retained);
    }

    static Map<String, Object> evidence(RetainedFiles retained, String hostSha, String pluginSha,
                                        Path template, boolean operatorTemplate) throws Exception {
        var files = retained.deliveries().stream().map(d -> Map.of("mode", d.operation().path("fileExport").path("mode").asText(),
                "operationId", d.operation().path("id").asText(), "sha256", digest(d.content()),
                "bytes", d.content().length, "baseSha256", d.operation().path("fileExport").path("baseSha256").asText())).toList();
        return Map.of("schemaVersion", 1, "harnessStatus", "PASSED", "realProductStatus", "NOT_EXECUTED",
                "hostSha256", hostSha, "pluginSha256", pluginSha, "templateSha256", PackagedPluginSupport.sha256(template),
                "templateOrigin", operatorTemplate ? "OPERATOR_SUPPLIED" : "SYNTHETIC", "files", files,
                "checked", List.of("installed plugin HTTP export", "review before binary download", "mode and basis replay binding",
                        "stable update identities", "independent new identities", "restart and retained basis reuse",
                        "artifact replacement rejection", "downloads and history without plugin", "unchanged synchronization checkpoint"));
    }

    static void retainOperatorOutputs(Path directory, RetainedFiles retained, Map<String, Object> evidence) throws Exception {
        Files.createDirectories(directory);
        for (Delivery delivery : retained.deliveries()) Files.write(directory.resolve(delivery.operation().path("id").asText() + ".qea"),
                delivery.content(), java.nio.file.StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("export-evidence.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n",
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private static void assertPcsRemainsReadOnly(PackagedPluginSupport.Application app, Scope scope, JsonNode profiles) throws Exception {
        var pcs = rows(profiles).stream().filter(p -> "sparx-oslc-am-2.0".equals(p.path("id").asText())).toList();
        assertThat(pcs).isNotEmpty().allSatisfy(p -> assertThat(strings(p.path("capabilities")))
                .doesNotContain("FILE_EXPORT", "BASED_FILE_EXPORT_V1", "CONDITIONAL_PUBLISH"));
        var request = Map.of("id", UUID.randomUUID().toString(), "name", "Unverified PCS remains read-only", "connectorId", "sparx-oslc-am-2.0",
                "profileVersion", pcs.getFirst().path("version").asText(), "authority", "PUBLISH_TARGET", "remoteProfile", "not-configured",
                "externalScope", Map.of("systemType", "SPARX", "repository", "not-a-live-repository"));
        assertThat(response(app.request("POST", scope.route("/api/integrations"), JSON.writeValueAsString(request), scope.headers()), 409)
                .path("code").asText()).isEqualTo("AUTHORITY_MODE");
    }

    private static RetainedFiles retain(PackagedPluginSupport.Application app, Scope scope, List<String> routes,
                                        List<Delivery> files, String pendingRoute, JsonNode pending) throws Exception {
        Map<String, JsonNode> overviews = new LinkedHashMap<>(), identities = new LinkedHashMap<>();
        for (String route : routes) { overviews.put(route, get(app, scope, route)); identities.put(route, get(app, scope, route + "/identities")); }
        return new RetainedFiles(scope, Map.copyOf(overviews), Map.copyOf(identities), get(app, scope, "/api/architecture/editor"),
                List.copyOf(files), pendingRoute, pending);
    }

    private static void assertRetained(PackagedPluginSupport.Application app, RetainedFiles retained) throws Exception {
        var editor = get(app, retained.scope(), "/api/architecture/editor");
        assertThat(editor.path("document").path("dsl")).isEqualTo(retained.architecture().path("document").path("dsl"));
        assertThat(editor.path("model")).isEqualTo(retained.architecture().path("model"));
        for (var entry : retained.overviews().entrySet()) {
            var overview = get(app, retained.scope(), entry.getKey());
            for (String field : List.of("connection", "current", "checkpoint", "history", "fileBases"))
                assertThat(overview.path(field)).as("Retained %s for %s", field, entry.getKey()).isEqualTo(entry.getValue().path(field));
            assertThat(overview.path("publicationAvailability").path("available").asBoolean()).isFalse();
            assertThat(get(app, retained.scope(), entry.getKey() + "/identities")).isEqualTo(retained.identities().get(entry.getKey()));
        }
        for (Delivery delivery : retained.deliveries()) {
            var operation = get(app, retained.scope(), delivery.route() + "/operations/" + delivery.operation().path("id").asText());
            assertThat(operation).as("Completed operation stays readable independently of plugin callbacks").isEqualTo(delivery.operation());
            assertThat(download(app, retained.scope(), delivery.route(), operation)).containsExactly(delivery.content());
        }
    }

    private static void assertPreview(JsonNode preview, String mode, Path basis, String version, String pluginSha) throws Exception {
        assertThat(preview.path("status").asText()).isEqualTo("PREVIEWED");
        var file = preview.path("fileExport");
        assertThat(file.path("mode").asText()).isEqualTo(mode);
        assertThat(file.path("identityNamespace").asText()).isNotBlank();
        assertThat(file.path("baseFileId").asText()).isNotBlank();
        assertThat(file.path("baseSha256").asText()).isEqualTo(PackagedPluginSupport.sha256(basis));
        assertThat(file.path("baseMediaType").asText()).isEqualTo(MEDIA_TYPE);
        assertThat(file.path("baseBytes").asLong()).isEqualTo(Files.size(basis));
        var metadata = preview.path("document").path("metadata");
        assertThat(metadata.path("taxonomy.plugin.id").asText()).isEqualTo("taxonomy.sparx");
        assertThat(metadata.path("taxonomy.plugin.version").asText()).isEqualTo(version);
        assertThat(metadata.path("taxonomy.plugin.sha256").asText()).isEqualTo(pluginSha);
        assertNoPayload(preview);
    }

    private static Delivery deliver(PackagedPluginSupport.Application app, Scope scope, String route, JsonNode preview) throws Exception {
        var reviewed = review(preview);
        var completed = post(app, scope, route + "/files", reviewed);
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("reviewFingerprint").asText()).isNotBlank();
        assertThat(completed.path("fileExport").path("outputFileId").asText()).isNotBlank();
        assertNoPayload(completed);
        byte[] content = download(app, scope, route, completed);
        assertThat(post(app, scope, route + "/files", reviewed)).as("An accepted review is idempotent").isEqualTo(completed);
        assertThat(download(app, scope, route, completed)).containsExactly(content);
        return new Delivery(route, completed, content);
    }

    private static byte[] download(PackagedPluginSupport.Application app, Scope scope, String route, JsonNode operation) throws Exception {
        var response = app.download(scope.route(route + "/operations/" + operation.path("id").asText() + "/file"), scope.headers());
        assertThat(response.statusCode()).as("Binary QEA download: %s", response.uri()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith(MEDIA_TYPE);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains(".qea");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        byte[] content = response.body();
        assertThat(content).startsWith("SQLite format 3\0".getBytes(StandardCharsets.US_ASCII));
        assertThat(digest(content)).isEqualTo(operation.path("fileExport").path("outputSha256").asText());
        assertThat(content.length).isEqualTo(operation.path("fileExport").path("outputBytes").asInt());
        return content;
    }

    private static void assertNoPayload(JsonNode operation) {
        assertThat(operation.path("resultFile").path("content").isMissingNode()
                || operation.path("resultFile").path("content").isNull()).as("Operation JSON does not contain SQLite payloads").isTrue();
        assertThat(operation.path("fileExport").has("baseContent")).isFalse();
        assertThat(operation.path("fileExport").has("outputContent")).isFalse();
    }

    private static JsonNode storedBasePreview(PackagedPluginSupport.Application app, Scope scope, String route, String baseId) throws Exception {
        return upload(app, scope, route, exportRequest(get(app, scope, route), "UPDATE_COPY", baseId), null, 200);
    }

    private static void unchangedCheckpoint(PackagedPluginSupport.Application app, Scope scope, String route, JsonNode before) throws Exception {
        var after = get(app, scope, route);
        assertThat(after.path("checkpoint")).as("File delivery is not remote acceptance").isEqualTo(before.path("checkpoint"));
        assertThat(after.path("current")).as("File delivery does not alter canonical architecture").isEqualTo(before.path("current"));
    }

    private static JsonNode readDelivered(PackagedPluginSupport.Application app, Scope scope, String route, Delivery delivery, Path file) throws Exception {
        Files.createDirectories(file.getParent()); Files.write(file, delivery.content());
        return inbound(app, scope, route, file).path("document");
    }

    private static void renameCanonicalElement(PackagedPluginSupport.Application app, Scope scope, String route) throws Exception {
        String id = matching(get(app, scope, route + "/identities"), "externalId", "ELEMENT:" + A).path("businessIdentity").asText();
        var editor = get(app, scope, "/api/architecture/editor");
        var request = Map.of("context", editor.path("document").path("context"), "metadata", commandMetadata("Review local file-export edit"),
                "kind", "UPDATE_ELEMENT", "id", id, "type", "Component", "properties", Map.of("title", LOCAL_TITLE));
        editorCommand(app, scope, "/api/architecture/editor/preview", request, editor);
        editorCommand(app, scope, "/api/architecture/editor/commands", request, editor);
        editor = get(app, scope, "/api/architecture/editor");
        assertThat(matching(editor.path("model").path("elements"), "id", id).path("title").asText()).isEqualTo(LOCAL_TITLE);
        editorCommand(app, scope, "/api/architecture/editor/checkpoints", Map.of("context", editor.path("document").path("context"),
                "metadata", commandMetadata("Freeze reviewed native-file source")), editor);
    }

    private static void editorCommand(PackagedPluginSupport.Application app, Scope scope, String route, Object request, JsonNode editor) throws Exception {
        var headers = new LinkedHashMap<>(scope.headers());
        headers.put("If-Match", "\"workspace-revision-" + editor.path("document").path("context").path("revision").asLong() + "\"");
        response(app.request("POST", scope.route(route), JSON.writeValueAsString(request), headers), 200);
    }

    private static Map<String, String> commandMetadata(String rationale) {
        String id = UUID.randomUUID().toString();
        return Map.of("commandId", id, "correlationId", id, "causationId", id, "rationale", rationale);
    }

    private static Scope workspace(PackagedPluginSupport.Application app) throws Exception {
        var repo = response(app.request("POST", "/api/repositories", JSON.writeValueAsString(Map.of("displayName", "Native QEA delivery",
                "slug", "packaged-qea-files", "description", "Synthetic installed-plugin contract", "visibility", "PRIVATE", "defaultBranch", "main"))), 200);
        String repository = repo.path("repositoryId").asText();
        var workspace = response(app.request("POST", "/api/repositories/" + encode(repository) + "/workspaces", JSON.writeValueAsString(Map.of(
                "displayName", "Reviewed native files", "description", "No EA product execution", "sourceBranch", "main"))), 200);
        assertThat(workspace.path("provisioningStatus").asText()).isEqualTo("READY");
        return new Scope(repository, workspace.path("workspaceId").asText(), workspace.path("currentBranch").asText());
    }

    private static String connection(PackagedPluginSupport.Application app, Scope scope, String authority, String model) throws Exception {
        String id = UUID.randomUUID().toString();
        var result = post(app, scope, "/api/integrations", Map.of("id", id, "name", "Reviewed QEA file " + authority,
                "connectorId", "sparx-qea", "profileVersion", "2", "authority", authority,
                "externalScope", Map.of("systemType", "SPARX", "repository", model)));
        assertThat(result.path("profileVersion").asText()).isEqualTo("2");
        return "/api/integrations/" + id;
    }

    private static JsonNode inbound(PackagedPluginSupport.Application app, Scope scope, String route, Path file) throws Exception {
        var request = Map.of("operationId", UUID.randomUUID().toString(), "expected", get(app, scope, route).path("current"),
                "mediaType", MEDIA_TYPE, "completeScope", false);
        return response(app.upload(scope.route(route + "/previews"), JSON.writeValueAsString(request), file, MEDIA_TYPE, scope.headers()), 200);
    }

    private static Map<String, Object> exportRequest(JsonNode overview, String mode, String baseId) {
        var request = new LinkedHashMap<String, Object>();
        request.put("operationId", UUID.randomUUID().toString()); request.put("expected", overview.path("current"));
        request.put("expectedExternalVersion", overview.path("checkpoint").path("externalVersion").asText(null));
        request.put("mode", mode); request.put("mediaType", MEDIA_TYPE); if (baseId != null) request.put("baseFileId", baseId);
        return request;
    }

    private static JsonNode upload(PackagedPluginSupport.Application app, Scope scope, String route, Object request, Path file, int status) throws Exception {
        return response(app.upload(scope.route(route + "/export-previews"), JSON.writeValueAsString(request), file, MEDIA_TYPE, scope.headers()), status);
    }

    private static Map<String, Object> review(JsonNode preview) {
        var decisions = new LinkedHashMap<String, String>();
        for (var change : rows(preview.path("changes"))) decisions.put(change.path("id").asText(), "ACCEPT");
        assertThat(decisions).isNotEmpty();
        return Map.of("operationId", preview.path("id").asText(), "previewFingerprint", preview.path("fingerprint").asText(),
                "decisions", decisions, "rationale", "Reviewed exact native QEA file and declared losses", "mappings", Map.of(), "endpoints", Map.of());
    }

    private static JsonNode get(PackagedPluginSupport.Application app, Scope scope, String route) throws Exception {
        return response(app.request("GET", scope.route(route), null, scope.headers()), 200);
    }
    private static JsonNode post(PackagedPluginSupport.Application app, Scope scope, String route, Object body) throws Exception {
        return response(app.request("POST", scope.route(route), JSON.writeValueAsString(body), scope.headers()), 200);
    }
    private static JsonNode response(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as("%s %s: %s", response.request().method(), response.uri(), response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
    private static JsonNode matching(JsonNode values, String property, String expected) {
        var matches = rows(values).stream().filter(v -> expected.equals(v.path(property).asText())).toList();
        assertThat(matches).as("Exactly one %s=%s in %s", property, expected, values).hasSize(1); return matches.getFirst();
    }
    private static List<JsonNode> rows(JsonNode value) {
        assertThat(value.isArray()).as("Expected array: %s", value).isTrue();
        var result = new ArrayList<JsonNode>(); value.forEach(result::add); return List.copyOf(result);
    }
    private static List<String> strings(JsonNode value) { return rows(value).stream().map(JsonNode::asText).toList(); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    record Delivery(String route, JsonNode operation, byte[] content) {}
    record RetainedFiles(Scope scope, Map<String, JsonNode> overviews, Map<String, JsonNode> identities,
                         JsonNode architecture, List<Delivery> deliveries, String pendingRoute, JsonNode pending) {}
    private record Scope(String repository, String workspace, String branch) {
        String route(String route) { return route + (route.contains("?") ? "&" : "?") + "repositoryId=" + encode(repository)
                + "&workspaceId=" + encode(workspace) + "&branch=" + encode(branch); }
        Map<String, String> headers() { return Map.of("X-Taxonomy-Workspace-Id", workspace); }
    }
}
