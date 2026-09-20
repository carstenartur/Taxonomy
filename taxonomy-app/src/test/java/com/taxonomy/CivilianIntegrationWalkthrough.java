package com.taxonomy;

import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.exchange.sparx.SparxMappingProfile;
import com.taxonomy.exchange.sparx.SparxXmiCodec;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Feed the real generated exchange file through review, canonical editing and Sparx delivery. */
final class CivilianIntegrationWalkthrough {
    static void verify(CivilianArchitectureAcceptanceTest app, byte[] archimate) throws Exception {
        var repository = app.post("/api/repositories", Map.of("displayName", "Civilian exchange QA",
                "slug", "civilian-exchange-" + UUID.randomUUID(), "description", "Isolated native exchange walkthrough",
                "visibility", "PRIVATE", "defaultBranch", "draft"), 200);
        var workspace = app.post("/api/repositories/" + repository.path("repositoryId").asText() + "/workspaces",
                Map.of("displayName", "Civilian exchange", "description", "", "sourceBranch", "draft"), 200);
        String scope = "?workspaceId=" + workspace.path("workspaceId").asText();
        String incoming = create(app, "Civilian snapshot import", ArchiMateExchangeCodec.PROFILE, "IMPORT_COPY", scope);
        var current = app.get(incoming + scope).get("current");
        var previewRequest = Map.of("operationId", UUID.randomUUID(), "expected", current,
                "mediaType", "application/archimate+xml", "completeScope", false);
        String boundary = "civilian-" + UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"request\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + app.json.writeValueAsString(previewRequest)
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"snapshot.xml\"\r\n"
                + "Content-Type: application/archimate+xml\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(archimate); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var response = app.http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + app.port + incoming + "/previews" + scope))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        ("admin:" + CivilianArchitectureAcceptanceTest.PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        var preview = app.json.readTree(response.body());
        app.save("integration-import-preview.json", preview);
        app.save("integration-import-review.json", app.json.valueToTree(review(preview)));
        var applied = app.post(incoming + "/apply" + scope, review(preview), 200);
        assertThat(applied.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(app.post(incoming + "/apply" + scope, review(preview), 200)).isEqualTo(applied);

        String outgoing = create(app, "Civilian Sparx delivery", SparxMappingProfile.PROFILE, "BIDIRECTIONAL", scope);
        var exportPreview = app.post(outgoing + "/export-previews" + scope, Map.of("operationId", UUID.randomUUID(),
                "expected", app.get(outgoing + scope).get("current")), 200);
        app.save("integration-sparx-preview.json", exportPreview);
        app.save("integration-sparx-review.json", app.json.valueToTree(review(exportPreview)));
        var delivered = app.post(outgoing + "/files" + scope, review(exportPreview), 200);
        assertThat(delivered.path("status").asText()).isEqualTo("COMPLETED");
        byte[] xmi = app.request("GET", outgoing + "/operations/" + delivered.path("id").asText() + "/file" + scope, null, 200).body();
        Files.write(app.output.resolve("architecture.sparx.xmi"), xmi);
        var codec = new SparxXmiCodec();
        var read = codec.read(xmi, "civilian-delivery", false);
        var replay = codec.read(codec.write(read), "civilian-delivery", false);
        assertThat(replay.artifacts()).isEqualTo(read.artifacts());
        assertThat(replay.relations()).isEqualTo(read.relations());
        assertThat(read.artifacts()).hasSize(38);
        var source = new ArchiMateExchangeCodec().read(archimate, "civilian-snapshot", false);
        assertThat(read.artifacts()).extracting(a -> a.title())
                .containsExactlyInAnyOrderElementsOf(source.artifacts().stream()
                        .filter(a -> a.kind() == com.taxonomy.extension.api.integration.IntegrationContracts.ArtifactKind.ELEMENT)
                        .map(a -> a.title()).toList());
        // These are explicit review boundaries, not an assertion of lossless export:
        // 8/44 generated candidates fail native type rules; 28/36 native relations
        // have no mapping in immutable Sparx profile v1, leaving 8 delivered relations.
        assertThat(exportPreview.path("document").path("relations")).hasSize(36);
        assertThat(read.relations()).hasSize(8);
        Set<String> expectedRelations = new TreeSet<>();
        var sourceLabels = new HashMap<String, String>();
        source.artifacts().forEach(a -> sourceLabels.put(a.id(), a.title()));
        @SuppressWarnings("unchecked")
        var decisions = (Map<String, String>) review(preview).get("decisions");
        for (var change : preview.path("changes")) {
            var after = change.path("after");
            if (!"RELATION".equals(after.path("kind").asText()) || !"ACCEPT".equals(decisions.get(change.path("id").asText()))) continue;
            var fields = after.path("extensions");
            String type = fields.path("canonicalType").asText();
            if (SparxMappingProfile.eaRelation(type) != null) expectedRelations.add(
                    sourceLabels.get(fields.path("source").asText()) + "|" + type + "|" + sourceLabels.get(fields.path("target").asText()));
        }
        var labels = new HashMap<String, String>();
        read.artifacts().forEach(a -> labels.put(a.id(), a.title()));
        assertThat(read.relations()).extracting(r -> labels.get(r.source()) + "|" + r.extensions().get("canonicalType") + "|" + labels.get(r.target()))
                .containsExactlyInAnyOrderElementsOf(expectedRelations);
        app.save("integration-quality.json", app.json.valueToTree(Map.of(
                "generatedRelations", 44, "nativeAcceptedRelations", 36, "nativeRejectedRelations", 8,
                "sparxDeliveredElements", 38, "sparxDeliveredRelations", 8, "sparxUnmappedRelations", 28,
                "sparxFileSha256", com.taxonomy.acceptance.CivilianExportQa.sha256(xmi),
                "productCompatibility", "NOT_EXECUTED", "reviewEvidence", List.of("integration-import-review.json", "integration-sparx-review.json"))));
        // A delivered file is not a remote acknowledgement and must not advance a checkpoint.
        assertThat(app.get(outgoing + scope).path("checkpoint").isNull()).isTrue();
        app.save("integration-sparx-delivery.json", delivered);
    }

    private static String create(CivilianArchitectureAcceptanceTest app, String name, String profile, String authority, String scope) throws Exception {
        UUID id = UUID.randomUUID();
        app.post("/api/integrations" + scope, Map.of("id", id, "name", name, "connectorId", profile,
                "authority", authority, "externalScope", Map.of("systemType", "Civilian reference",
                        "repository", "urn:taxonomy:civilian-flood:" + id)), 200);
        return "/api/integrations/" + id;
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
