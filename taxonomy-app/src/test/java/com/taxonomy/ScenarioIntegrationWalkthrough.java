package com.taxonomy;

import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Feed the real generated exchange file through review, canonical editing and Sparx delivery. */
final class ScenarioIntegrationWalkthrough {
    static void verify(ScenarioArchitectureAcceptanceTest app, JsonNode snapshot, JsonNode projection, byte[] archimate) throws Exception {
        var repository = app.post("/api/repositories", Map.of("displayName", "Scenario exchange QA",
                "slug", "scenario-exchange-" + UUID.randomUUID(), "description", "Isolated native exchange walkthrough",
                "visibility", "PRIVATE", "defaultBranch", "draft"), 200);
        var workspace = app.post("/api/repositories/" + repository.path("repositoryId").asText() + "/workspaces",
                Map.of("displayName", "Scenario exchange", "description", "", "sourceBranch", "draft"), 200);
        String scope = "?workspaceId=" + workspace.path("workspaceId").asText();
        String incoming = create(app, "Scenario snapshot import", ArchiMateExchangeCodec.PROFILE, "1", "IMPORT_COPY", scope);
        var current = app.get(incoming + scope).get("current");
        var previewRequest = Map.of("operationId", UUID.randomUUID(), "expected", current,
                "mediaType", "application/archimate+xml", "completeScope", false);
        String boundary = "scenario-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + app.json.writeValueAsString(previewRequest)
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"snapshot.xml\"\r\n"
                + "Content-Type: application/archimate+xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(archimate); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = app.http.send(app.authenticatedRequest("POST", incoming + "/previews" + scope)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        var preview = app.json.readTree(response.body());
        app.save("integration-import-preview.json", preview);
        app.save("integration-import-review.json", app.json.valueToTree(review(preview)));
        assertAllRelationsAccepted(preview, review(preview), "Snapshot import");
        var applied = app.post(incoming + "/apply" + scope, review(preview), 200);
        assertThat(applied.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(app.post(incoming + "/apply" + scope, review(preview), 200)).isEqualTo(applied);

        String outgoing = create(app, "Scenario Sparx delivery", SparxMappingProfile.PROFILE, "3", "BIDIRECTIONAL", scope);
        var exportPreview = app.post(outgoing + "/export-previews" + scope, Map.of("operationId", UUID.randomUUID(),
                "expected", app.get(outgoing + scope).get("current")), 200);
        app.save("integration-sparx-preview.json", exportPreview);
        app.save("integration-sparx-review.json", app.json.valueToTree(review(exportPreview)));
        assertAllRelationsAccepted(exportPreview, review(exportPreview), "Sparx v3 delivery");
        var delivered = app.post(outgoing + "/files" + scope, review(exportPreview), 200);
        assertThat(delivered.path("status").asText()).isEqualTo("COMPLETED");
        byte[] xmi = app.request("GET", outgoing + "/operations/" + delivered.path("id").asText() + "/file" + scope, null, 200).body();
        Files.write(app.output.resolve("architecture.sparx.xmi"), xmi);
        var codec = new SparxXmiCodec("3");
        // This delivery is the complete semantic model of the isolated workspace.
        // The read-only check does not import tombstones or acknowledge a checkpoint.
        var read = codec.read(xmi, "scenario-delivery", true);
        var replay = codec.read(codec.write(read), "scenario-delivery", true);
        var source = new ArchiMateExchangeCodec().read(archimate, "scenario-snapshot", false);
        var sourceElements = source.artifacts().stream()
                .filter(a -> a.kind() == ArtifactKind.ELEMENT).toList();
        assertThat(replay.artifacts()).isEqualTo(read.artifacts());
        assertThat(replay.relations()).isEqualTo(read.relations());
        assertThat(read.artifacts()).hasSize(sourceElements.size());
        assertThat(read.artifacts()).extracting(a -> a.title())
                .containsExactlyInAnyOrderElementsOf(sourceElements.stream().map(a -> a.title()).toList());
        // Delivery preserves the actual generated graph through a new connection, not a
        // separately fabricated Sparx example. Compare oriented, typed endpoints and every
        // exported Taxonomy property, including scoped rationale and quoted provenance.
        assertThat(exportPreview.path("document").path("relations")).hasSize(source.relations().size());
        assertThat(read.relations()).hasSize(source.relations().size());
        Map<String, Artifact> sourceByTitle = new TreeMap<>(), deliveredByTitle = new TreeMap<>();
        var sourceLabels = new HashMap<String, String>();
        var deliveredLabels = new HashMap<String, String>();
        for (var artifact : sourceElements) {
            assertThat(sourceByTitle.put(artifact.title(), artifact)).isNull();
            sourceLabels.put(artifact.id(), artifact.title());
        }
        for (var artifact : read.artifacts()) {
            assertThat(deliveredByTitle.put(artifact.title(), artifact)).isNull();
            deliveredLabels.put(artifact.id(), artifact.title());
        }
        for (var artifact : sourceElements) {
            var actual = deliveredByTitle.get(artifact.title());
            assertThat(actual.extensions().get("canonicalType")).isEqualTo(artifact.extensions().get("canonicalType"));
            assertTaxonomyPropertiesRetained(artifact.attributes(), artifact.extensions(), actual.attributes(), artifact.title());
        }
        Map<String, Relation> expectedRelations = new TreeMap<>(), actualRelations = new TreeMap<>();
        for (var relation : source.relations()) {
            assertThat(expectedRelations.put(relationKey(relation, sourceLabels), relation)).isNull();
        }
        for (var relation : read.relations()) {
            assertThat(actualRelations.put(relationKey(relation, deliveredLabels), relation)).isNull();
        }
        assertThat(actualRelations.keySet()).containsExactlyInAnyOrderElementsOf(expectedRelations.keySet());
        for (var entry : expectedRelations.entrySet()) {
            var actual = actualRelations.get(entry.getKey());
            assertThat(actual.extensions().get("direction")).isEqualTo("Source -> Destination");
            assertTaxonomyPropertiesRetained(entry.getValue().attributes(), entry.getValue().extensions(),
                    actual.attributes(), entry.getKey());
        }
        var quality = new LinkedHashMap<String, Object>();
        quality.put("generatedRelations", source.relations().size());
        quality.put("nativeAcceptedRelations", source.relations().size());
        quality.put("nativeRejectedRelations", 0);
        quality.put("sparxProfile", SparxMappingProfile.PROFILE + "@3");
        quality.put("sparxDeliveredElements", sourceElements.size());
        quality.put("sparxDeliveredRelations", read.relations().size());
        quality.put("sparxUnmappedRelations", 0);
        quality.put("sparxFileSha256", com.taxonomy.acceptance.ScenarioExportQa.sha256(xmi));
        quality.put("productCompatibility", "NOT_EXECUTED");
        quality.put("evaluationScope", "FUNCTIONAL_MODEL_AND_EVIDENCE_NOT_DESKTOP_CERTIFICATION");
        quality.put("reviewEvidence", List.of("integration-import-review.json", "integration-sparx-review.json"));
        app.save("integration-quality.json", app.json.valueToTree(quality));
        app.save("architecture-quality.json", app.json.valueToTree(
                com.taxonomy.acceptance.ScenarioArchitectureQuality.verify(snapshot, projection, read, xmi)));
        // A delivered file is not a remote acknowledgement and must not advance a checkpoint.
        assertThat(app.get(outgoing + scope).path("checkpoint").isNull()).isTrue();
        app.save("integration-sparx-delivery.json", delivered);
        verifyNativeV2(app, scope);
    }

    /** Contract fixture at the remote boundary; all application APIs and persistence remain real. */
    private static void verifyNativeV2(ScenarioArchitectureAcceptanceTest app, String ignoredScope) throws Exception {
        var repository = app.post("/api/repositories", Map.of("displayName", "Native V2 contract", "slug", "native-v2-" + UUID.randomUUID(),
                "description", "Isolated contract fixture", "visibility", "PRIVATE", "defaultBranch", "draft"), 200);
        var workspace = app.post("/api/repositories/" + repository.path("repositoryId").asText() + "/workspaces",
                Map.of("displayName", "Native V2", "description", "", "sourceBranch", "draft"), 200);
        String scope = "?workspaceId=" + workspace.path("workspaceId").asText();
        long project = app.post("/api/projects" + scope, Map.of("projectKey", "NATIVE-" + UUID.randomUUID().toString().substring(0, 8),
                "title", "Native exchange requirements", "status", "ACTIVE"), 201).path("id").asLong();
        UUID connection = UUID.randomUUID(); String path = "/api/integrations/" + connection;
        app.post("/api/integrations" + scope, Map.of("id", connection, "name", "Native V2 contract", "connectorId", SparxMappingProfile.PROFILE,
                "profileVersion", "2", "authority", "BIDIRECTIONAL", "projectId", project,
                "externalScope", Map.of("systemType", "Contract fixture", "repository", "urn:native-contract:" + connection)), 200);
        String root = "{10000000-0000-4000-8000-000000000000}", pkg = "{10000000-0000-4000-8000-000000000001}",
                element = "{10000000-0000-4000-8000-000000000002}", req = "{10000000-0000-4000-8000-000000000003}", rel = "{10000000-0000-4000-8000-000000000004}";
        var document = new ExchangeDocument(SparxMappingProfile.PROFILE, "2", "contract-v2", true, "",
                List.of(new Artifact(pkg, ArtifactKind.SPECIFICATION, "Package", "Flood package", "Contract fixture", Map.of(), Map.of()),
                        new Artifact(element, ArtifactKind.ELEMENT, "Component", "Flood reader", "", Map.of(), Map.of("canonicalType", "Component")),
                        new Artifact(req, ArtifactKind.REQUIREMENT, "Class", "Flood requirement", "Publish traceable observations", Map.of(), Map.of())),
                List.of(new Relation(rel, "Dependency", req, element, Map.of(), Map.of("canonicalType", "DEPENDS_ON", "direction", "Source -> Destination"))),
                List.of(new Placement("placement:" + pkg, root, null, pkg, 0, Map.of()), new Placement("placement:" + element, root, "placement:" + pkg, element, 0, Map.of()),
                        new Placement("placement:" + req, root, "placement:" + pkg, req, 1, Map.of())), Map.of("identifier", root, "title", "Contract fixture"), List.of());
        byte[] fixture = new SparxXmiCodec("2").write(document);
        java.nio.file.Files.write(app.output.resolve("native-browser-fixture.xmi"), fixture);
        JsonNode preview = uploadXmi(app, path, scope, fixture);
        var choices = app.get(path + "/operations/" + preview.path("id").asText() + "/endpoint-options" + scope).path("external");
        var decision = new TreeMap<String, String>(); String connectorChange = null;
        for (var change : preview.path("changes")) { decision.put(change.path("id").asText(), "ACCEPT"); if (change.path("externalId").asText().equals("RELATION:" + rel)) connectorChange = change.path("id").asText(); }
        Map<String, Object> review = Map.of("operationId", preview.path("id").asText(), "previewFingerprint", preview.path("fingerprint").asText(), "decisions", decision,
                "rationale", "Contract fixture: explicitly map the requirement connector", "endpoints", Map.of(connectorChange, Map.of("projection", "REQUIREMENT_MAPPING",
                        "sourceInternalIdentity", choices.path(req).path("businessIdentity").asText(), "targetInternalIdentity", choices.path(element).path("businessIdentity").asText())));
        app.post(path + "/apply" + scope, review, 200);
        JsonNode editor = app.get("/api/architecture/editor" + scope);
        String packageId = choices.path(pkg).path("businessIdentity").asText();
        String commandId = UUID.randomUUID().toString();
        var command = Map.of("context", editor.path("document").path("context"), "metadata", Map.of("commandId", commandId, "correlationId", commandId, "causationId", commandId, "rationale", "Rename native package"),
                "kind", "UPDATE_PACKAGE", "id", packageId, "properties", Map.of("title", "Reviewed flood package"));
        editorRequest(app, "/api/architecture/editor/preview" + scope, command, editor.path("document").path("context").path("revision").asLong());
        editorRequest(app, "/api/architecture/editor/commands" + scope, command, editor.path("document").path("context").path("revision").asLong());
        editor = app.get("/api/architecture/editor" + scope);
        String parentCommand = UUID.randomUUID().toString(), parentId = "pkg-" + parentCommand;
        var createParent = Map.of("context", editor.path("document").path("context"), "metadata", Map.of("commandId", parentCommand, "correlationId", parentCommand, "causationId", parentCommand, "rationale", "Create native parent package"),
                "kind", "CREATE_PACKAGE", "properties", Map.of("title", "Flood programme"));
        editorRequest(app, "/api/architecture/editor/commands" + scope, createParent, editor.path("document").path("context").path("revision").asLong());
        editor = app.get("/api/architecture/editor" + scope);
        String moveCommand = UUID.randomUUID().toString();
        var movePackage = Map.of("context", editor.path("document").path("context"), "metadata", Map.of("commandId", moveCommand, "correlationId", moveCommand, "causationId", moveCommand, "rationale", "Move native package atomically"),
                "kind", "SET_PACKAGE_PLACEMENTS", "completeParentScopes", List.of("", parentId), "placements", List.of(
                        Map.of("kind", "PACKAGE", "memberId", parentId, "parentPackageId", "", "position", 0),
                        Map.of("kind", "PACKAGE", "memberId", packageId, "parentPackageId", parentId, "position", 0)));
        editorRequest(app, "/api/architecture/editor/preview" + scope, movePackage, editor.path("document").path("context").path("revision").asLong());
        editorRequest(app, "/api/architecture/editor/commands" + scope, movePackage, editor.path("document").path("context").path("revision").asLong());
        var overview = app.get(path + scope);
        var export = app.post(path + "/export-previews" + scope, Map.of("operationId", UUID.randomUUID(), "expected", overview.path("current"), "expectedExternalVersion", overview.path("checkpoint").path("externalVersion").asText()), 200);
        var exportDecisions = new TreeMap<String, String>(); export.path("changes").forEach(c -> exportDecisions.put(c.path("id").asText(), "ACCEPT"));
        app.post(path + "/files" + scope, Map.of("operationId", export.path("id").asText(), "previewFingerprint", export.path("fingerprint").asText(), "decisions", exportDecisions, "rationale", "Deliver reviewed package and mapping"), 200);
        byte[] delivered = app.request("GET", path + "/operations/" + export.path("id").asText() + "/file" + scope, null, 200).body();
        assertThat(new SparxXmiCodec("2").read(delivered, "delivery", true).artifacts()).anyMatch(a -> a.id().equals(pkg) && a.title().equals("Reviewed flood package"));
        long firstReturnRevision = -1;
        for (int i = 0; i < 2; i++) {
            var returned = uploadXmi(app, path, scope, delivered); var decisions = new TreeMap<String, String>(); returned.path("changes").forEach(c -> decisions.put(c.path("id").asText(), "ACCEPT"));
            app.post(path + "/apply" + scope, Map.of("operationId", returned.path("id").asText(), "previewFingerprint", returned.path("fingerprint").asText(), "decisions", decisions, "rationale", "Reviewed native round trip"), 200);
            long revision = app.get(path + scope).path("current").path("semanticRevision").asLong();
            if (i == 0) firstReturnRevision = revision;
            else assertThat(revision).as("Second native import must not append a semantic operation").isEqualTo(firstReturnRevision);
        }
        var finalEditor = app.get("/api/architecture/editor" + scope);
        assertThat(finalEditor.path("model").path("mappings")).isNotEmpty();
        assertThat(finalEditor.path("model").path("packages").toString()).contains(packageId, "Reviewed flood package");
        app.save("integration-native-context.json", app.json.valueToTree(Map.of("scope", scope, "editorContext", finalEditor.path("document").path("context"),
                "connection", connection.toString(), "packageId", packageId, "productCompatibility", "NOT_EXECUTED", "fixture", "contract")));
        app.save("integration-native-review.json", app.json.valueToTree(review));
    }
    static JsonNode editorRequest(ScenarioArchitectureAcceptanceTest app, String path, Object body, long revision) throws Exception {
        var response = app.http.send(app.authenticatedRequest("POST", path)
                .header("Content-Type", "application/json").header("If-Match", "\"workspace-revision-" + revision + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(app.json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        return app.json.readTree(response.body());
    }
    static JsonNode uploadXmi(ScenarioArchitectureAcceptanceTest app, String path, String scope, byte[] file) throws Exception {
        String boundary = "native-" + UUID.randomUUID();
        var request = Map.of("operationId", UUID.randomUUID(), "expected", app.get(path + scope).path("current"), "mediaType", "application/xmi+xml", "completeScope", true);
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\nContent-Type: application/json\r\n\r\n" + app.json.writeValueAsString(request)
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"contract.xmi\"\r\nContent-Type: application/xmi+xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = app.http.send(app.authenticatedRequest("POST", path + "/previews" + scope)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200); return app.json.readTree(response.body());
    }

    private static String create(ScenarioArchitectureAcceptanceTest app, String name, String profile, String version, String authority, String scope) throws Exception {
        UUID id = UUID.randomUUID();
        app.post("/api/integrations" + scope, Map.of("id", id, "name", name, "connectorId", profile, "profileVersion", version,
                "authority", authority, "externalScope", Map.of("systemType", "Scenario reference",
                        "repository", "urn:taxonomy:scenario-flood:" + id)), 200);
        return "/api/integrations/" + id;
    }

    private static String relationKey(Relation relation, Map<String, String> labels) {
        return labels.get(relation.source()) + "|" + relation.extensions().get("canonicalType") + "|" + labels.get(relation.target());
    }

    @SuppressWarnings("unchecked")
    private static void assertAllRelationsAccepted(JsonNode preview, Map<String, Object> review, String stage) {
        var decisions = (Map<String, String>) review.get("decisions");
        for (var change : preview.path("changes")) {
            if ("RELATION".equals(change.at("/after/kind").asText())) {
                assertThat(decisions.get(change.path("id").asText()))
                        .as("%s must not discard generated relationship %s", stage, change.at("/after/id").asText())
                        .isEqualTo("ACCEPT");
            }
        }
        for (var loss : preview.at("/document/losses")) {
            if ("UNSUPPORTED".equals(loss.path("disposition").asText())) {
                assertThat(preview.at("/document/relations"))
                        .as("%s unsupported relationship loss: %s", stage, loss)
                        .noneMatch(relation -> relation.path("id").asText().equals(loss.path("artifactId").asText()));
            }
        }
    }

    private static void assertTaxonomyPropertiesRetained(Map<String, String> properties, Map<String, String> definitions,
                                                         Map<String, String> delivered, String identity) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        for (var property : properties.entrySet()) {
            String name = definitions.get("definition:" + property.getKey());
            if (name == null || !name.startsWith("taxonomy.")) continue;
            var parsed = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    property.getValue().getBytes(StandardCharsets.UTF_8)));
            var values = parsed.getElementsByTagNameNS(ArchiMateExchangeCodec.NS, "value");
            assertThat(values.getLength()).as("Single source property %s on %s", name, identity).isEqualTo(1);
            // Sparx reserves taxonomy.id for its own GUID. The original exchange
            // identifier is explicitly preserved as sourceId and in source XML.
            String targetName = name.equals("taxonomy.id") ? "taxonomy.sourceId" : name;
            assertThat(delivered.get("tag:" + targetName)).as("Retained %s on %s", name, identity)
                    .isEqualTo(values.item(0).getTextContent());
        }
    }

    private static Map<String, Object> review(JsonNode preview) {
        var types = new HashMap<String, String>();
        for (var artifact : preview.path("document").path("artifacts"))
            types.put(artifact.path("id").asText(), artifact.path("extensions").path("canonicalType").asText());
        var unsupported = new HashSet<String>();
        for (var loss : preview.path("document").path("losses"))
            if (loss.path("disposition").asText().equals("UNSUPPORTED")) unsupported.add(loss.path("artifactId").asText());
        var decisions = new TreeMap<String, String>();
        for (var change : preview.path("changes")) {
            String id = change.path("id").asText();
            var after = change.path("after");
            boolean reject = unsupported.contains(after.path("id").asText()) || unsupported.contains(id)
                    || after.path("kind").asText().equals("VIEW")
                    || after.path("kind").asText().equals("PLACEMENT");
            if (after.path("kind").asText().equals("RELATION")) {
                var rules = com.taxonomy.dsl.validation.DslValidator.relationTypeRules()
                        .get(after.path("extensions").path("canonicalType").asText());
                String source = types.get(after.path("extensions").path("source").asText());
                String target = types.get(after.path("extensions").path("target").asText());
                if (rules != null) reject |= !rules.getOrDefault(
                        com.taxonomy.dsl.model.TaxonomyRootTypes.rootFor(source), Set.of()).contains(
                        com.taxonomy.dsl.model.TaxonomyRootTypes.rootFor(target));
            }
            decisions.put(id, reject ? "REJECT" : "ACCEPT");
        }
        return Map.of("operationId", preview.path("id").asText(), "previewFingerprint", preview.path("fingerprint").asText(),
                "decisions", decisions, "rationale", "Reference review: retain supported elements and connectors satisfying native type rules; reject incompatible generated candidates, layouts and unsupported mappings.");
    }
}
