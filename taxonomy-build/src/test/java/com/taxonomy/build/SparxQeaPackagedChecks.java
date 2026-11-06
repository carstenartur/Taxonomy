package com.taxonomy.build;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Public HTTP checks against the hand-authored standalone plugin fixtures, not an EA compatibility claim. */
final class SparxQeaPackagedChecks {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MODEL = "{10000000-0000-0000-0000-000000000000}";
    private static final String ROOT = "{20000000-0000-0000-0000-000000000001}";
    private static final String CHILD = "{20000000-0000-0000-0000-000000000002}";
    private static final String A = "{30000000-0000-0000-0000-000000000001}";
    private static final String B = "{30000000-0000-0000-0000-000000000002}";
    private static final String RELATION = "{40000000-0000-0000-0000-000000000001}";
    private static final String VIEW = "{50000000-0000-0000-0000-000000000001}";
    private static final String MEDIA_TYPE = "application/vnd.sparx.qea";

    static RetainedState reviewedImports(PackagedPluginSupport.Application app, Path base, Path renamed,
                                         String pluginVersion, String pluginDigest) throws Exception {
        Scope scope = createWorkspace(app);
        var profiles = get(app, scope, "/api/integrations/profiles");
        var profile = matching(profiles, "id", "sparx-qea");
        assertThat(profile.path("version").asText()).isEqualTo("1");
        assertThat(strings(profile.path("capabilities"))).containsExactlyInAnyOrder(
                "FILE_IMPORT", "ARCHITECTURE_MODEL", "CANONICAL_PROJECTION_V1");
        assertThat(strings(profile.path("mediaTypes"))).contains(MEDIA_TYPE);

        String connection = UUID.randomUUID().toString();
        var created = post(app, scope, "/api/integrations", connection(connection, "IMPORT_COPY"));
        assertThat(created.path("id").asText()).isEqualTo(connection);
        assertThat(created.path("profileVersion").asText()).isEqualTo("1");
        String route = "/api/integrations/" + connection;
        var before = get(app, scope, "/api/architecture/editor");
        assertThat(before.path("document").path("context").path("writeMode").asText()).isEqualTo("PRIVATE_WORKSPACE");
        assertThat(rows(before.path("model").path("elements"))).isEmpty();
        assertThat(rows(get(app, scope, route + "/identities"))).isEmpty();

        var preview = preview(app, scope, route, base);
        assertThat(preview.path("status").asText()).isEqualTo("PREVIEWED");
        assertThat(rows(preview.path("document").path("artifacts")))
                .extracting(item -> item.path("id").asText()).containsExactlyInAnyOrder(ROOT, CHILD, A, B, VIEW);
        assertThat(rows(preview.path("changes"))).isNotEmpty()
                .allSatisfy(change -> assertThat(change.path("kind").asText()).isEqualTo("ADD"));
        var provenance = preview.path("document").path("metadata");
        assertThat(provenance.path("taxonomy.plugin.id").asText()).isEqualTo("taxonomy.sparx");
        assertThat(provenance.path("taxonomy.plugin.version").asText()).isEqualTo(pluginVersion);
        assertThat(provenance.path("taxonomy.plugin.sha256").asText()).isEqualTo(pluginDigest);
        assertThat(provenance.path("identifier").asText()).isEqualTo(MODEL);
        assertThat(get(app, scope, "/api/architecture/editor").path("document").path("dsl"))
                .as("Uploading a preview must not write canonical architecture").isEqualTo(before.path("document").path("dsl"));
        assertThat(rows(get(app, scope, route + "/identities"))).isEmpty();

        var applied = apply(app, scope, route, preview, "Reviewed both components, package hierarchy, dependency and diagram membership");
        assertThat(applied.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(applied.path("resultCommit").asText()).matches("[0-9a-f]{40}");
        Map<String, String> identities = identities(get(app, scope, route + "/identities"));
        assertCanonicalModel(get(app, scope, "/api/architecture/editor"), identities, false);
        var firstState = get(app, scope, route).path("current");
        assertThat(firstState.path("semanticRevision").asLong()).isGreaterThan(0);

        var repeated = preview(app, scope, route, base);
        assertThat(rows(repeated.path("changes"))).isNotEmpty()
                .allSatisfy(change -> assertThat(change.path("kind").asText()).isEqualTo("UNCHANGED"));
        assertThat(apply(app, scope, route, repeated, "Reviewed unchanged snapshot").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(identities(get(app, scope, route + "/identities"))).isEqualTo(identities);
        var repeatedState = get(app, scope, route).path("current");
        assertThat(repeatedState.path("semanticRevision")).isEqualTo(firstState.path("semanticRevision"));
        assertThat(repeatedState.path("commitId")).isEqualTo(firstState.path("commitId"));
        assertCanonicalModel(get(app, scope, "/api/architecture/editor"), identities, false);

        var changed = preview(app, scope, route, renamed);
        assertThat(matching(changed.path("changes"), "externalId", "ELEMENT:" + A).path("kind").asText()).isEqualTo("UPDATE");
        assertThat(matching(changed.path("changes"), "externalId", "PLACEMENT:member:" + A).path("kind").asText()).isEqualTo("MOVE");
        assertThat(matching(changed.path("changes"), "externalId", "RELATION:" + RELATION).path("kind").asText()).isEqualTo("UNCHANGED");
        assertThat(rows(changed.path("changes"))).allSatisfy(change ->
                assertThat(change.path("kind").asText()).isNotIn("ADD", "CONFLICT", "REMOVE_CANDIDATE"));
        assertCanonicalModel(get(app, scope, "/api/architecture/editor"), identities, false);
        assertThat(apply(app, scope, route, changed, "Reviewed the component rename and move to Root").path("status").asText())
                .isEqualTo("COMPLETED");
        assertThat(identities(get(app, scope, route + "/identities"))).isEqualTo(identities);
        assertCanonicalModel(get(app, scope, "/api/architecture/editor"), identities, true);
        assertThat(get(app, scope, route).path("current").path("semanticRevision").asLong())
                .isGreaterThan(firstState.path("semanticRevision").asLong());

        var unavailableWrite = app.request("POST", scope.route("/api/integrations"),
                JSON.writeValueAsString(connection(UUID.randomUUID().toString(), "BIDIRECTIONAL")), scope.headers());
        assertThat(response(unavailableWrite, 409).path("code").asText()).isEqualTo("AUTHORITY_MODE");
        return new RetainedState(scope, route, get(app, scope, route), get(app, scope, route + "/identities"),
                get(app, scope, "/api/architecture/editor"), get(app, scope, "/api/integrations"));
    }

    static void absentPlugin(PackagedPluginSupport.Application app, RetainedState retained, Path fixture) throws Exception {
        assertThat(app.request("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        Scope scope = retained.scope();
        assertThat(rows(get(app, scope, "/api/integrations/profiles")))
                .noneMatch(profile -> "sparx-qea".equals(profile.path("id").asText()));
        assertRetainedState(app, retained);

        var unavailablePreview = previewResponse(app, scope, retained.route(), fixture);
        assertThat(response(unavailablePreview, 409).path("code").asText()).isEqualTo("PROFILE_VERSION_CHANGED");
        assertRetainedState(app, retained);

        var unavailable = app.request("POST", scope.route("/api/integrations"),
                JSON.writeValueAsString(connection(UUID.randomUUID().toString(), "IMPORT_COPY")), scope.headers());
        assertThat(response(unavailable, 400).path("code").asText()).isEqualTo("UNKNOWN_CONNECTOR");
        assertRetainedState(app, retained);
    }

    private static void assertRetainedState(PackagedPluginSupport.Application app, RetainedState retained) throws Exception {
        var architecture = get(app, retained.scope(), "/api/architecture/editor");
        assertThat(architecture.path("document").path("dsl"))
                .as("Removing the plugin and rejecting new imports must preserve the canonical source")
                .isEqualTo(retained.architecture().path("document").path("dsl"));
        assertThat(architecture.path("model"))
                .as("Imported elements, packages, relationships and views remain readable without the plugin")
                .isEqualTo(retained.architecture().path("model"));
        var mappings = get(app, retained.scope(), retained.route() + "/identities");
        assertThat(rows(mappings)).as("All recorded external identities survive plugin removal")
                .containsExactlyInAnyOrderElementsOf(rows(retained.identityMappings()));
        assertCanonicalModel(architecture, identities(mappings), true);
        var overview = get(app, retained.scope(), retained.route());
        assertThat(overview.path("publicationAvailability").path("available").asBoolean()).isFalse();
        assertThat(overview.path("publicationAvailability").path("reasonCode").asText()).isEqualTo("PROFILE_VERSION_CHANGED");
        for (String field : List.of("connection", "current", "checkpoint", "history")) {
            assertThat(overview.path(field)).as("Plugin removal must preserve integration %s", field)
                    .isEqualTo(retained.overview().path(field));
        }
        assertThat(rows(get(app, retained.scope(), "/api/integrations")))
                .as("The retained connection stays readable and rejected requests create no connections")
                .containsExactlyInAnyOrderElementsOf(rows(retained.connections()));
    }

    private static Scope createWorkspace(PackagedPluginSupport.Application app) throws Exception {
        var repository = response(app.request("POST", "/api/repositories", JSON.writeValueAsString(Map.of(
                "displayName", "Packaged QEA acceptance", "slug", "packaged-qea", "description", "Synthetic snapshot contract",
                "visibility", "PRIVATE", "defaultBranch", "main"))), 200);
        String repositoryId = repository.path("repositoryId").asText();
        assertThat(repositoryId).isNotBlank();
        assertThat(repository.path("visibility").asText()).isEqualTo("PRIVATE");
        var workspace = response(app.request("POST", "/api/repositories/" + encode(repositoryId) + "/workspaces",
                JSON.writeValueAsString(Map.of("displayName", "QEA private working copy", "description", "Reviewed snapshot import",
                        "sourceBranch", "main"))), 200);
        assertThat(workspace.path("sourceRepositoryId").asText()).isEqualTo(repositoryId);
        assertThat(workspace.path("provisioningStatus").asText()).isEqualTo("READY");
        String workspaceId = workspace.path("workspaceId").asText(), branch = workspace.path("currentBranch").asText();
        assertThat(workspaceId).isNotBlank();
        assertThat(branch).isNotBlank();
        return new Scope(repositoryId, workspaceId, branch);
    }

    private static Map<String, Object> connection(String id, String authority) {
        return Map.of("id", id, "name", "Synthetic QEA snapshots", "connectorId", "sparx-qea", "profileVersion", "1",
                "authority", authority, "externalScope", Map.of("systemType", "SPARX", "repository", MODEL));
    }

    private static JsonNode preview(PackagedPluginSupport.Application app, Scope scope, String route, Path fixture) throws Exception {
        return response(previewResponse(app, scope, route, fixture), 200);
    }

    private static HttpResponse<String> previewResponse(PackagedPluginSupport.Application app, Scope scope,
                                                         String route, Path fixture) throws Exception {
        var current = get(app, scope, route).path("current");
        String request = JSON.writeValueAsString(Map.of("operationId", UUID.randomUUID().toString(),
                "expected", current, "mediaType", MEDIA_TYPE, "completeScope", false));
        return app.upload(scope.route(route + "/previews"), request, fixture, MEDIA_TYPE, scope.headers());
    }

    private static JsonNode apply(PackagedPluginSupport.Application app, Scope scope, String route,
                                  JsonNode preview, String rationale) throws Exception {
        Map<String, String> decisions = new LinkedHashMap<>();
        for (var change : rows(preview.path("changes"))) decisions.put(change.path("id").asText(), "ACCEPT");
        assertThat(decisions).as("Explicit human decisions cover every previewed change").isNotEmpty();
        var review = Map.of("operationId", preview.path("id").asText(), "previewFingerprint", preview.path("fingerprint").asText(),
                "decisions", decisions, "rationale", rationale, "mappings", Map.of(), "endpoints", Map.of());
        var result = post(app, scope, route + "/apply", review);
        assertThat(result.path("review").path("rationale").asText()).isEqualTo(rationale);
        assertThat(result.path("reviewFingerprint").asText()).isNotBlank();
        return result;
    }

    private static void assertCanonicalModel(JsonNode editor, Map<String, String> identities, boolean moved) {
        String a = identity(identities, "ELEMENT:" + A), b = identity(identities, "ELEMENT:" + B);
        String root = identity(identities, "SPECIFICATION:" + ROOT), child = identity(identities, "SPECIFICATION:" + CHILD);
        String view = identity(identities, "VIEW:" + VIEW);
        assertThat(List.of(a, b, root, child, view)).doesNotHaveDuplicates();
        var model = editor.path("model");
        assertThat(rows(model.path("elements"))).extracting(element -> element.path("id").asText())
                .containsExactlyInAnyOrder(a, b);
        var first = matching(model.path("elements"), "id", a);
        assertThat(first.path("type").asText()).isEqualTo("Component");
        assertThat(first.path("title").asText()).isEqualTo(moved ? "Renamed component" : "Same name");
        assertThat(first.path("packageId").asText()).as("Diagram membership cannot replace the component's package")
                .isEqualTo(moved ? root : child);
        var second = matching(model.path("elements"), "id", b);
        assertThat(second.path("title").asText()).isEqualTo("Same name");
        assertThat(second.path("packageId").asText()).isEqualTo(child);
        assertThat(rows(model.path("packages"))).extracting(pkg -> pkg.path("id").asText()).containsExactlyInAnyOrder(root, child);
        assertThat(matching(model.path("packages"), "id", root).path("title").asText()).isEqualTo("Root");
        assertThat(matching(model.path("packages"), "id", root).path("parentId").asText("")).isEmpty();
        var childPackage = matching(model.path("packages"), "id", child);
        assertThat(childPackage.path("title").asText()).isEqualTo("Child");
        assertThat(childPackage.path("parentId").asText()).isEqualTo(root);
        assertThat(rows(model.path("views"))).hasSize(1);
        var diagram = matching(model.path("views"), "id", view);
        assertThat(diagram.path("title").asText()).isEqualTo("Overview");
        assertThat(strings(diagram.path("includes"))).containsExactlyInAnyOrder(a, b);
        assertThat(rows(model.path("relations"))).hasSize(1);
        var relation = rows(model.path("relations")).getFirst();
        assertThat(relation.path("sourceId").asText()).isEqualTo(a);
        assertThat(relation.path("relationType").asText()).isEqualTo("DEPENDS_ON");
        assertThat(relation.path("targetId").asText()).isEqualTo(b);
    }

    private static Map<String, String> identities(JsonNode values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var mapping : rows(values)) {
            assertThat(mapping.path("removed").asBoolean()).isFalse();
            String external = mapping.path("externalId").asText(), internal = mapping.path("businessIdentity").asText();
            assertThat(external).isNotBlank();
            assertThat(internal).isNotBlank();
            assertThat(result.put(external, internal)).as("One mapping per stable external identity: %s", external).isNull();
        }
        return Map.copyOf(result);
    }

    private static String identity(Map<String, String> mappings, String external) {
        assertThat(mappings).containsKey(external);
        return mappings.get(external);
    }

    private static JsonNode matching(JsonNode values, String property, String expected) {
        var matching = rows(values).stream().filter(value -> expected.equals(value.path(property).asText())).toList();
        assertThat(matching).as("Exactly one %s=%s in %s", property, expected, values).hasSize(1);
        return matching.getFirst();
    }

    private static List<JsonNode> rows(JsonNode value) {
        assertThat(value.isArray()).as("Expected JSON array: %s", value).isTrue();
        var result = new ArrayList<JsonNode>();
        value.forEach(result::add);
        return List.copyOf(result);
    }

    private static List<String> strings(JsonNode value) { return rows(value).stream().map(JsonNode::asText).toList(); }

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

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    record RetainedState(Scope scope, String route, JsonNode overview, JsonNode identityMappings,
                         JsonNode architecture, JsonNode connections) {}

    private record Scope(String repository, String workspace, String branch) {
        String route(String route) {
            return route + (route.contains("?") ? "&" : "?") + "repositoryId=" + encode(repository)
                    + "&workspaceId=" + encode(workspace) + "&branch=" + encode(branch);
        }
        Map<String, String> headers() { return Map.of("X-Taxonomy-Workspace-Id", workspace); }
    }
}
