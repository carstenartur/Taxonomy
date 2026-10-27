package com.taxonomy.acceptance;

import com.taxonomy.diagram.*;
import com.taxonomy.export.VisioDiagramService;
import com.taxonomy.export.VisioPackageBuilder;
import com.taxonomy.portfolio.workbench.VisioExportContextService;
import com.taxonomy.visio.VisioExportMetadata;
import com.taxonomy.visio.VisioProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioVisioExportQaTest {
    private static final Instant FIRST = Instant.parse("2026-10-27T09:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-10-27T09:00:01.987654321Z");
    private static final String SNAPSHOT = "scenario-fixture";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void independentDownloadsRetainTheSameSourceAcrossDifferentExportTimes() throws Exception {
        // The longer timestamp wraps the header, leaving 37 source lines per page instead of 38.
        var source = Map.of("taxonomy.requirementText", VisioProperty.text(
                String.join("\n", Collections.nCopies(38, "Original requirement line."))));
        byte[] standalone = export(FIRST, false, graph(), source), bundle = export(SECOND, true, graph(), source);
        var standaloneManifest = JSON.readTree(ScenarioExportQa.unzip(standalone).get("taxonomy/manifest.json"));
        var bundledManifest = JSON.readTree(ScenarioExportQa.unzip(bundle).get("manifest.json")).path("handoff");
        assertThat(standaloneManifest.at("/authority/taxonomy.requirementContextPages/value").asText()).isEqualTo("1");
        assertThat(bundledManifest.at("/authority/taxonomy.requirementContextPages/value").asText()).isEqualTo("2");
        ScenarioExportQa.verifyVisio(graph(), SNAPSHOT,
                Map.of("architecture.vsdx", standalone, "architecture.visio.zip", bundle));
    }

    @Test void differentFrozenAuthorityCannotHideBehindAnIndependentDownloadTime() throws Exception {
        byte[] bundle = export(SECOND, true, graph(), Map.of("taxonomy.workspaceId", VisioProperty.text("other-workspace")));
        rejects(export(FIRST, false), bundle);
    }

    @Test void changedSemanticShapeRecordsAreRejectedEvenWhenIdentitiesStillMatch() throws Exception {
        var changed = new DiagramModel(graph().title(), List.of(
                new DiagramNode("BP-1", "Changed process", "Business Processes", .8, false, 0), graph().nodes().get(1)),
                graph().edges(), graph().layout());
        rejects(export(FIRST, false), export(SECOND, true, changed, Map.of()));
    }

    @Test void changedConnectorEndpointsAreRejectedEvenWhenBothEndpointsExist() throws Exception {
        var changed = new DiagramModel(graph().title(), graph().nodes(),
                List.of(new DiagramEdge("uses", "CR-1", "BP-1", "USES", .7, "STRUCTURAL")), graph().layout());
        rejects(export(FIRST, false), export(SECOND, true, changed, Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"artifact", "mappingProfile"})
    void bundleChecksumsMustBindTheirOwnActualFiles(String field) throws Exception {
        byte[] bundle = rewrite(export(SECOND, true), "manifest.json", source -> {
            var manifest = JSON.readTree(source);
            ((ObjectNode) manifest.path(field)).put("sha256", "0".repeat(64));
            return JSON.writeValueAsString(manifest);
        });
        rejects(export(FIRST, false), bundle);
    }

    @Test void outerHandoffMustExactlyMatchTheEmbeddedManifest() throws Exception {
        byte[] bundle = rewrite(export(SECOND, true), "manifest.json", source -> {
            var manifest = JSON.readTree(source);
            ((ObjectNode) manifest.at("/handoff/authority/taxonomy.exportedBy")).put("value", "other-exporter");
            return JSON.writeValueAsString(manifest);
        });
        rejects(export(FIRST, false), bundle);
    }

    @Test void aSelfDeclaredChecksumCannotAuthorizeDifferentProfileBytes() throws Exception {
        var parts = ScenarioExportQa.unzip(export(FIRST, false));
        String oldHash = ScenarioExportQa.sha256(parts.get("taxonomy/mapping-profile.json"));
        byte[] changedProfile = (new String(parts.get("taxonomy/mapping-profile.json"), StandardCharsets.UTF_8) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String changedHash = ScenarioExportQa.sha256(changedProfile);
        parts.put("taxonomy/mapping-profile.json", changedProfile);
        var manifest = (ObjectNode) JSON.readTree(parts.get("taxonomy/manifest.json"));
        manifest.put("profileSha256", changedHash);
        parts.put("taxonomy/manifest.json", JSON.writeValueAsBytes(manifest));
        parts.put("docProps/custom.xml", new String(parts.get("docProps/custom.xml"), StandardCharsets.UTF_8)
                .replace(oldHash, changedHash).getBytes(StandardCharsets.UTF_8));
        rejects(zip(parts), export(SECOND, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"schemaVersion", "exportProfile", "profileSha256", "certification", "losses"})
    void stableHandoffFieldsCannotChangeBetweenIndependentDownloads(String field) throws Exception {
        byte[] vsdx = rewrite(export(FIRST, false), "taxonomy/manifest.json", source -> {
            var manifest = (ObjectNode) JSON.readTree(source);
            if (field.equals("losses")) manifest.putArray(field).addObject().put("rationale", "Unexpected loss");
            else manifest.put(field, "changed");
            return JSON.writeValueAsString(manifest);
        });
        rejects(vsdx, export(SECOND, true));
    }

    @Test void declaredRequirementPageCountMustMatchTheActualSourcePages() throws Exception {
        byte[] vsdx = rewrite(export(FIRST, false), "taxonomy/manifest.json", source -> {
            var manifest = JSON.readTree(source);
            ((ObjectNode) manifest.at("/authority/taxonomy.requirementContextPages")).put("value", "999");
            return JSON.writeValueAsString(manifest);
        });
        rejects(vsdx, export(SECOND, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"docProps/custom.xml", "visio/pages/page1.xml"})
    void nativeTimestampsMustMatchThatDownloadsManifest(String part) throws Exception {
        byte[] vsdx = rewrite(export(FIRST, false), part, text -> text.replace(FIRST.toString(), SECOND.toString()));
        rejects(vsdx, export(SECOND, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"authority", "shape", "label"})
    void manifestAndVisibleContextMustHaveOneValidExportTime(String field) throws Exception {
        byte[] vsdx = rewrite(export(FIRST, false), "taxonomy/manifest.json", source -> {
            var manifest = JSON.readTree(source);
            if (field.equals("authority")) {
                ((ObjectNode) manifest.at("/authority/taxonomy.exportedAt")).put("value", "not-an-instant");
            } else {
                for (var shape : manifest.path("pages").get(0).path("shapes")) {
                    if (!shape.path("properties").has("taxonomy.exportedAt")) continue;
                    if (field.equals("shape")) ((ObjectNode) shape.at("/properties/taxonomy.exportedAt")).put("value", SECOND.toString());
                    else ((ObjectNode) shape).put("label", shape.path("label").asText().replace(FIRST.toString(), SECOND.toString()));
                }
            }
            return JSON.writeValueAsString(manifest);
        });
        rejects(vsdx, export(SECOND, true));
    }

    @Test void malformedXmlInTheBundledDownloadIsStillRejectedAfterItsChecksumIsUpdated() throws Exception {
        var bundle = ScenarioExportQa.unzip(export(SECOND, true));
        bundle.put("diagram.vsdx", rewrite(bundle.get("diagram.vsdx"), "visio/document.xml", ignored -> "<invalid"));
        var manifest = JSON.readTree(bundle.get("manifest.json"));
        ((ObjectNode) manifest.path("artifact")).put("sha256", ScenarioExportQa.sha256(bundle.get("diagram.vsdx")));
        bundle.put("manifest.json", JSON.writeValueAsBytes(manifest));
        byte[] standalone = export(FIRST, false), corruptBundle = zip(bundle);
        assertThatThrownBy(() -> ScenarioExportQa.verifyVisio(graph(), SNAPSHOT,
                Map.of("architecture.vsdx", standalone, "architecture.visio.zip", corruptBundle)))
                .isInstanceOf(org.xml.sax.SAXParseException.class);
    }

    private static void rejects(byte[] vsdx, byte[] bundle) {
        assertThatThrownBy(() -> ScenarioExportQa.verifyVisio(graph(), SNAPSHOT,
                Map.of("architecture.vsdx", vsdx, "architecture.visio.zip", bundle))).isInstanceOf(AssertionError.class);
    }

    private static DiagramModel graph() {
        return new DiagramModel("Scoped architecture", List.of(
                new DiagramNode("BP-1", "Requested process", "Business Processes", .8, false, 0),
                new DiagramNode("CR-1", "Supporting service", "Core Services", .7, false, 0)),
                List.of(new DiagramEdge("uses", "BP-1", "CR-1", "USES", .7, "STRUCTURAL")),
                new DiagramLayout("LR", true));
    }

    private static byte[] export(Instant exportedAt, boolean bundle) throws Exception {
        return export(exportedAt, bundle, graph(), Map.of());
    }

    private static byte[] export(Instant exportedAt, boolean bundle, DiagramModel graph,
                                 Map<String, VisioProperty> overrides) throws Exception {
        var context = new VisioExportContextService(null, "scenario-test", "https://taxonomy.example",
                Clock.fixed(exportedAt, ZoneOffset.UTC)).create(42L, 90L, "a".repeat(25));
        var properties = new LinkedHashMap<>(context.documentProperties());
        properties.put("taxonomy.snapshotId", VisioProperty.text(SNAPSHOT));
        properties.put("taxonomy.snapshotCreatedAt", VisioProperty.text("2026-09-08T10:00:00Z"));
        properties.put("taxonomy.requirementText", VisioProperty.text("Retain this original requirement."));
        properties.put("taxonomy.workspaceId", VisioProperty.text("scenario-workspace"));
        properties.putAll(overrides);
        var document = new VisioDiagramService().convert(graph,
                new VisioExportMetadata(properties, Map.of(), Map.of(), List.of()));
        var builder = new VisioPackageBuilder();
        return bundle ? builder.buildBundle(document) : builder.build(document);
    }

    private static byte[] rewrite(byte[] archive, String part, UnaryOperator<String> change) throws Exception {
        var entries = ScenarioExportQa.unzip(archive);
        entries.put(part, change.apply(new String(entries.get(part), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
