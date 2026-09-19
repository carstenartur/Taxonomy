package com.taxonomy.acceptance;

import com.taxonomy.archimate.exchange.ArchiMateExchangeReader;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.exchange.ArchiMateExchangeCodec;
import com.taxonomy.catalog.service.importer.StructurizrDslParser;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Assertions consume real downloaded bytes and the persisted scene, never fabricated outputs. */
public final class CivilianExportQa {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CivilianExportQa() { }

    public static Map<String, Object> verify(JsonNode projection, Map<String, byte[]> artifacts,
                                            Path output, boolean render) throws Exception {
        String snapshot = projection.path("snapshotId").asText();
        String requirement = projection.path("requirementKey").asText();
        DiagramModel expected = JSON.treeToValue(projection.get("diagram"), DiagramModel.class);
        assertThat(expected.title()).contains(requirement).doesNotContain("archview.");
        var reader = new ArchiMateExchangeReader();
        var imported = reader.read(artifacts.get("architecture.archimate.xml"));
        assertThat(reader.toDiagram(imported)).isEqualTo(expected);
        var exchange = new ArchiMateExchangeCodec();
        var inbound = exchange.read(artifacts.get("architecture.archimate.xml"), snapshot, true);
        var replay = exchange.read(exchange.write(inbound), snapshot, true);
        assertThat(replay.artifacts()).extracting(artifact -> artifact.id())
                .containsExactlyInAnyOrderElementsOf(inbound.artifacts().stream().map(artifact -> artifact.id()).toList());
        assertThat(replay.relations()).extracting(relation -> relation.id())
                .containsExactlyInAnyOrderElementsOf(inbound.relations().stream().map(relation -> relation.id()).toList());
        var archimateBundle = unzip(artifacts.get("architecture.archimate.zip"));
        assertThat(archimateBundle.get("model.archimate.xml")).isEqualTo(artifacts.get("architecture.archimate.xml"));
        var archimateManifest = JSON.readTree(archimateBundle.get("manifest.json"));
        assertThat(archimateManifest.path("xmlSha256").asText())
                .isEqualTo(sha256(artifacts.get("architecture.archimate.xml")));

        var visioBundle = unzip(artifacts.get("architecture.visio.zip"));
        assertThat(visioBundle.get("diagram.vsdx")).isEqualTo(artifacts.get("architecture.vsdx"));
        var visio = unzip(artifacts.get("architecture.vsdx"));
        var visioManifest = JSON.readTree(visio.get("taxonomy/manifest.json"));
        assertThat(visioManifest.at("/authority/taxonomy.snapshotId/value").asText()).isEqualTo(snapshot);
        Set<String> shapeIds = new TreeSet<>();
        Set<String> relationIds = new TreeSet<>();
        for (JsonNode page : visioManifest.path("pages")) {
            Set<String> localIds = new HashSet<>();
            for (JsonNode shape : page.path("shapes")) {
                localIds.add(shape.path("shapeId").asText());
                shapeIds.add(shape.path("properties").path("taxonomy.id").path("value").asText());
            }
            for (JsonNode relation : page.path("relationships")) {
                assertThat(localIds).contains(relation.path("sourceShapeId").asText(), relation.path("targetShapeId").asText());
                relationIds.add(relation.path("properties").path("taxonomy.id").path("value").asText());
            }
        }
        Set<String> semanticIds = new TreeSet<>();
        expected.nodes().stream().filter(node -> !node.container()).forEach(node -> semanticIds.add(node.id()));
        assertThat(shapeIds).containsExactlyInAnyOrderElementsOf(semanticIds);
        assertThat(relationIds).containsExactlyInAnyOrderElementsOf(expected.edges().stream()
                .filter(edge -> semanticIds.contains(edge.sourceId()) && semanticIds.contains(edge.targetId()))
                .map(edge -> edge.id()).toList());
        for (var file : visio.entrySet()) {
            if (file.getKey().endsWith(".xml") || file.getKey().endsWith(".rels")) xml(file.getValue());
        }

        var svg = xml(artifacts.get("architecture.svg"));
        assertThat(svg.getDocumentElement().getLocalName()).isEqualTo("svg");
        assertThat(svg.getElementsByTagName("script").getLength()).isZero();
        Set<String> renderedIds = new TreeSet<>();
        var groups = svg.getElementsByTagName("g");
        for (int i = 0; i < groups.getLength(); i++) {
            var group = (org.w3c.dom.Element) groups.item(i);
            if (group.hasAttribute("data-node-id")) renderedIds.add(group.getAttribute("data-node-id"));
        }
        assertThat(renderedIds).containsExactlyInAnyOrderElementsOf(expected.nodes().stream().map(node -> node.id()).toList());
        JsonNode scene = projection.path("scene");
        for (JsonNode node : scene.path("nodes")) {
            assertThat(node.path("label").asText()).isNotBlank();
            assertThat(node.path("x").asDouble()).isGreaterThanOrEqualTo(0);
            assertThat(node.path("y").asDouble()).isGreaterThanOrEqualTo(0);
            assertThat(node.path("x").asDouble() + node.path("width").asDouble()).isLessThanOrEqualTo(scene.path("width").asDouble());
            assertThat(node.path("y").asDouble() + node.path("height").asDouble()).isLessThanOrEqualTo(scene.path("height").asDouble());
        }
        try (var document = Loader.loadPDF(artifacts.get("architecture.pdf"))) {
            var glyphs = new ArrayList<org.apache.pdfbox.text.TextPosition>();
            String text = new PDFTextStripper() {
                @Override protected void writeString(String value, List<org.apache.pdfbox.text.TextPosition> positions)
                        throws java.io.IOException {
                    glyphs.addAll(positions);
                    super.writeString(value, positions);
                }
            }.getText(document);
            assertThat(text).contains(snapshot, requirement);
            for (String id : renderedIds) assertThat(text).contains(id);
            assertThat(glyphs).isNotEmpty().allSatisfy(glyph ->
                    assertThat(glyph.getFontSizeInPt()).as("PDF glyph: %s", glyph.getUnicode()).isGreaterThanOrEqualTo(7));
            assertThat(document.getNumberOfPages()).isPositive();
            if (render) ImageIO.write(new PDFRenderer(document).renderImageWithDPI(0, 110), "png",
                    output.resolve("architecture-pdf.png").toFile());
        }
        String reportHtml = new String(artifacts.get("decision.html"), StandardCharsets.UTF_8);
        assertThat(reportHtml).contains(requirement, snapshot, "CIV-FLOOD-001");
        JsonNode decision = JSON.readTree(artifacts.get("decision.json"));
        assertThat(decision.toString()).contains(requirement, snapshot);
        try (var document = new XWPFDocument(new ByteArrayInputStream(artifacts.get("decision.docx")));
             var extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).contains(requirement, snapshot, "CIV-FLOOD-001");
            assertThat(document.getTables()).isNotEmpty();
        }
        for (String format : List.of("html", "markdown", "json")) {
            assertThat(new String(artifacts.get("report." + format), StandardCharsets.UTF_8)).contains(requirement);
        }
        JSON.readTree(artifacts.get("report.json"));
        try (var document = new XWPFDocument(new ByteArrayInputStream(artifacts.get("report.docx")));
             var extractor = new XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).contains(requirement);
        }
        String structurizrFile = artifacts.keySet().stream().filter(name -> name.startsWith("adapter-structurizr.")).findFirst().orElseThrow();
        var structurizr = new StructurizrDslParser().parse(new ByteArrayInputStream(artifacts.get(structurizrFile)));
        assertThat(structurizr.elements()).extracting(element -> element.name())
                .containsExactlyInAnyOrderElementsOf(expected.nodes().stream().filter(node -> !node.container()).map(node -> node.label()).toList());
        String mermaidFile = artifacts.keySet().stream().filter(name -> name.startsWith("adapter-mermaid.")).findFirst().orElseThrow();
        String mermaid = new String(artifacts.get(mermaidFile), StandardCharsets.UTF_8);
        assertThat(mermaid).contains("flowchart");
        for (var node : expected.nodes()) assertThat(mermaid).contains(node.label());
        // Report observations as observations: an automatically generated candidate is not an approved design.
        Set<String> connected = new HashSet<>();
        expected.edges().forEach(edge -> { connected.add(edge.sourceId()); connected.add(edge.targetId()); });
        var isolated = semanticIds.stream().filter(id -> !connected.contains(id)).toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("snapshotId", snapshot);
        report.put("semanticElements", semanticIds.size());
        report.put("relationships", expected.edges().size());
        report.put("isolatedElements", isolated);
        report.put("architectureState", "GENERATED_CANDIDATE_REQUIRES_DOMAIN_REVIEW");
        report.put("automaticChecks", List.of("ArchiMate semantic round trip", "Visio identity and endpoint parity",
                "ZIP and manifest checksums", "SVG identity and scene bounds", "PDF text, provenance and minimum 7pt glyph size",
                "DOCX/HTML/JSON report provenance", "All four registered diagram adapters",
                "ArchiMate integration codec round trip", "Structurizr local importer label parity",
                "Legacy Markdown/HTML/DOCX/JSON report endpoints"));
        report.put("notCertified", List.of("Sparx EA desktop", "Microsoft Visio desktop", "operational flood-warning design"));
        Map<String, String> hashes = new TreeMap<>();
        artifacts.forEach((name, bytes) -> hashes.put(name, sha256(bytes)));
        report.put("artifactSha256", hashes);
        return report;
    }

    public static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        assertThat(bytes).isNotNull();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertThat(entries).doesNotContainKey(entry.getName());
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertThat(entries).isNotEmpty();
        return entries;
    }
    private static org.w3c.dom.Document xml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
