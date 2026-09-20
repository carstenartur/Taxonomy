package com.taxonomy;

import com.taxonomy.interop.publication.PublicationContractProvider;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Real authenticated application routes prepare a browser-reviewable test-only publication. */
final class CivilianPublicationWalkthrough {
    static JsonNode prepare(CivilianArchitectureAcceptanceTest app, PublicationContractProvider provider) throws Exception {
        var repository = app.post("/api/repositories", Map.of("displayName", "Publication contract — TEST ONLY", "slug", "publication-" + UUID.randomUUID(), "description", "Genuine HTTP contract acceptance; not PCS", "visibility", "PRIVATE", "defaultBranch", "draft"), 200);
        var workspace = app.post("/api/repositories/" + repository.path("repositoryId").asText() + "/workspaces", Map.of("displayName", "Publication contract — TEST ONLY", "description", "", "sourceBranch", "draft"), 200);
        String scope = "?repositoryId=" + repository.path("repositoryId").asText() + "&workspaceId=" + workspace.path("workspaceId").asText() + "&branch=draft";
        UUID connection = UUID.randomUUID(); String path = "/api/integrations/" + connection;
        app.post("/api/integrations" + scope, Map.of("id", connection, "name", "Publication contract — TEST ONLY / NUR TEST", "connectorId", PublicationContractProvider.PROFILE, "profileVersion", "1", "authority", "BIDIRECTIONAL", "externalScope", PublicationContractProvider.SCOPE.externalScope()), 200);
        for (String title : List.of("Flood observations", "Verified warning channel", "Civilian response coordination")) {
            var editor = app.get("/api/architecture/editor" + scope); String id = UUID.randomUUID().toString();
            var command = Map.of("context", editor.path("document").path("context"), "metadata", metadata(id, "Prepare reviewed contract publication"), "kind", "CREATE_ELEMENT", "type", "System", "properties", Map.of("title", title));
            CivilianIntegrationWalkthrough.editorRequest(app, "/api/architecture/editor/commands" + scope, command, editor.path("document").path("context").path("revision").asLong());
        }
        var editor = app.get("/api/architecture/editor" + scope); String checkpoint = UUID.randomUUID().toString();
        CivilianIntegrationWalkthrough.editorRequest(app, "/api/architecture/editor/checkpoints" + scope, Map.of("context", editor.path("document").path("context"), "metadata", metadata(checkpoint, "Freeze publication source")), editor.path("document").path("context").path("revision").asLong());
        var overview = app.get(path + scope);
        var preview = app.post(path + "/publication-previews" + scope, Map.of("operationId", UUID.randomUUID(), "expected", overview.path("current"), "mode", "PUSH", "scope", PublicationContractProvider.SCOPE, "expectedExternalRevision", provider.revision()), 200);
        assertThat(preview.path("preview").path("changes")).hasSize(3);
        provider.closeAfterWrite(2);
        var result = app.json.valueToTree(Map.of("scope", scope, "connection", connection, "operation", preview.path("operationId").asText(), "provider", "taxonomy-publication-contract-v1 — TEST ONLY", "productCompatibility", "NOT_EXECUTED"));
        app.save("publication-context.json", result); app.save("publication-preview.json", preview);
        return result;
    }
    static JsonNode prepareDivergence(CivilianArchitectureAcceptanceTest app, PublicationContractProvider provider, JsonNode context) throws Exception {
        String scope = context.path("scope").asText(), path = "/api/integrations/" + context.path("connection").asText();
        var editor = app.get("/api/architecture/editor" + scope); String id = UUID.randomUUID().toString();
        CivilianIntegrationWalkthrough.editorRequest(app, "/api/architecture/editor/commands" + scope,
                Map.of("context", editor.path("document").path("context"), "metadata", metadata(id, "Keep a reviewed divergence visible"), "kind", "CREATE_ELEMENT", "type", "System", "properties", Map.of("title", "Review pending evacuation plans")), editor.path("document").path("context").path("revision").asLong());
        editor = app.get("/api/architecture/editor" + scope); String checkpoint = UUID.randomUUID().toString();
        CivilianIntegrationWalkthrough.editorRequest(app, "/api/architecture/editor/checkpoints" + scope,
                Map.of("context", editor.path("document").path("context"), "metadata", metadata(checkpoint, "Freeze explicit divergence for review")), editor.path("document").path("context").path("revision").asLong());
        var preview = app.post(path + "/publication-previews" + scope,
                Map.of("operationId", UUID.randomUUID(), "expected", app.get(path + scope).path("current"), "mode", "PUSH", "scope", PublicationContractProvider.SCOPE, "expectedExternalRevision", provider.revision()), 200);
        assertThat(preview.path("preview").path("changes")).isNotEmpty();
        return preview;
    }
    static Map<String, Object> review(JsonNode preview, String resolution) {
        var decisions = new TreeMap<String, String>(); preview.path("preview").path("changes").forEach(c -> decisions.put(c.path("id").asText(), resolution));
        return Map.of("review", Map.of("operationId", preview.path("operationId").asText(), "previewFingerprint", preview.path("preview").path("fingerprint").asText(), "decisions", Map.of(), "rationale", "Reviewed test-only conditional publication"), "resolutions", decisions);
    }
    private static Map<String, String> metadata(String id, String rationale) { return Map.of("commandId", id, "correlationId", id, "causationId", id, "rationale", rationale); }
}
