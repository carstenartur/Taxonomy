package com.taxonomy.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.diagram.*;
import com.taxonomy.visio.*;
import org.apache.poi.xdgf.usermodel.XmlVisioDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.parallel.Isolated("Temporarily changes the default timezone to verify ZIP reproducibility")
@org.junit.jupiter.api.parallel.Execution(org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD)
class VisioHandoffContractTest {
    @TempDir Path renderDirectory;
    private static final String NS = "http://schemas.microsoft.com/office/visio/2012/main";
    private static final JsonMapper JSON = new JsonMapper();

    static DiagramModel representative() {
        return new DiagramModel("VSDX acceptance – Unicode & duplicate labels", List.of(
                new DiagramNode("CP-01", "Überblick 日本語 & <secure>", "Capabilities", .9, true, 0, 2, true, null, false),
                new DiagramNode("BP-02", "Überblick 日本語 & <secure>", "Business Processes", .7, false, 1, 2, true, null, false),
                new DiagramNode("CS-03", "Service 🚀", "Core Services", .4, false, 2, 2, false, null, false)),
                List.of(new DiagramEdge("relation/α", "CP-01", "BP-02", "SUPPORTS", .8, "trace"),
                        new DiagramEdge("relation/β", "BP-02", "CS-03", "USES", .6, "dependency")),
                new DiagramLayout("LR", true));
    }

    static VisioDocument fixtureDocument() {
        var authority = Map.of("taxonomy.snapshotId", VisioProperty.text("synthetic-965"),
                "taxonomy.repositoryId", VisioProperty.text("synthetic-repository"),
                "taxonomy.authoritativeCommit", VisioProperty.text("0000000000000000000000000000000000000000"),
                "taxonomy.generatedAt", VisioProperty.text("2026-09-08T00:00:00Z"),
                "taxonomy.timestampPolicy", VisioProperty.text("SOURCE_SNAPSHOT_CREATED_AT"));
        return new VisioDiagramService().convert(representative(), new VisioExportMetadata(authority,
                Map.of("CP-01", Map.of("taxonomy.reviewStatus", VisioProperty.text("CONFIRMED"),
                        "taxonomy.decisionRationale", VisioProperty.text("=RUNADDON(\"literal only\") & reviewed"))),
                Map.of(), List.of()));
    }

    @Test void nativeShapeDataReconstructsCanonicalIdentitiesTypesScoresAndEndpoints() throws Exception {
        Map<String, String> parts = parts(new VisioPackageBuilder().build(fixtureDocument()));
        Map<String, Map<String, String>> values = shapeData(parts.get("visio/pages/page1.xml"));
        for (var node : representative().nodes()) {
            Map<String, String> data = values.get(node.id());
            assertNotNull(data);
            assertEquals(node.type(), data.get("taxonomy.type"));
            assertEquals(node.label(), data.get("label"));
            assertEquals(node.relevance(), Double.parseDouble(data.get("taxonomy.relevance")));
            assertEquals(node.anchor() ? "1" : "0", data.get("taxonomy.anchor"));
            assertEquals(node.selectedForImpact() ? "1" : "0", data.get("taxonomy.selectedForImpact"));
        }
        for (var edge : representative().edges()) {
            Map<String, String> data = values.get(edge.id());
            assertNotNull(data);
            assertEquals(edge.sourceId(), data.get("taxonomy.sourceId"));
            assertEquals(edge.targetId(), data.get("taxonomy.targetId"));
            assertEquals(edge.relationType(), data.get("taxonomy.type"));
            assertEquals(edge.relationCategory(), data.get("taxonomy.relationCategory"));
            assertEquals(edge.relevance(), Double.parseDouble(data.get("taxonomy.relevance")));
        }
        assertEquals("CONFIRMED", values.get("CP-01").get("taxonomy.reviewStatus"));
        assertEquals("=RUNADDON(\"literal only\") & reviewed", values.get("CP-01").get("taxonomy.decisionRationale"));
        assertEquals(Set.of("CP-01", "BP-02", "relation/α"), shapeData(parts.get("visio/pages/page2.xml")).keySet());
    }

    @Test void poiIndependentlyLoadsBothPagesAndEveryGlueEndpoint() throws Exception {
        try (var reader = new XmlVisioDocument(new ByteArrayInputStream(new VisioPackageBuilder().build(fixtureDocument())))) {
            assertEquals(2, reader.getPages().size());
            int shapes = 0, connections = 0;
            for (var page : reader.getPages()) {
                assertTrue(page.getPageSize().getWidth() >= 11);
                assertTrue(page.getPageSize().getHeight() >= 8.5);
                shapes += page.getContent().getTopLevelShapes().size();
                connections += page.getContent().getConnections().size();
            }
            assertEquals(8, shapes);
            assertEquals(6, connections);
        }
    }

    @Test void bundleChecksumsBindExactlyTheVsdxAndProfile() throws Exception {
        Map<String, byte[]> bundle = bytes(new VisioPackageBuilder().buildBundle(fixtureDocument()));
        assertEquals(Set.of("diagram.vsdx", "manifest.json", "mapping-profile.json"), bundle.keySet());
        JsonNode manifest = JSON.readTree(bundle.get("manifest.json"));
        assertEquals(VisioHandoffProfile.sha256(bundle.get("diagram.vsdx")), manifest.at("/artifact/sha256").asText());
        assertEquals(VisioHandoffProfile.sha256(bundle.get("mapping-profile.json")), manifest.at("/mappingProfile/sha256").asText());
        Map<String, byte[]> inner = bytes(bundle.get("diagram.vsdx"));
        assertEquals(JSON.readTree(inner.get("taxonomy/manifest.json")), manifest.get("handoff"));
        assertArrayEquals(bundle.get("mapping-profile.json"), inner.get("taxonomy/mapping-profile.json"));
    }

    @Test void secondaryConsumerCanRenderEveryPageWithResolvedStyles() throws Exception {
        try (var reader = new XmlVisioDocument(new ByteArrayInputStream(new VisioPackageBuilder().build(fixtureDocument())))) {
            int index = 0;
            for (var page : reader.getPages()) {
                Path image = renderDirectory.resolve("page-" + (++index) + ".png");
                org.apache.poi.xdgf.util.VsdxToPng.renderToPng(page, image.toFile(), 72,
                        new org.apache.poi.xdgf.usermodel.shape.ShapeRenderer());
                var rendered = javax.imageio.ImageIO.read(image.toFile());
                assertNotNull(rendered);
                assertTrue(rendered.getWidth() >= 700);
                assertTrue(Files.size(image) > 1000);
            }
        }
    }

    @Test void shuffledCanonicalCollectionsKeepNumericIdsLayoutAndBytesStable() throws Exception {
        DiagramModel graph = representative();
        List<DiagramNode> nodes = new ArrayList<>(graph.nodes()); Collections.reverse(nodes);
        List<DiagramEdge> edges = new ArrayList<>(graph.edges()); Collections.reverse(edges);
        var shuffled = new DiagramModel(graph.title(), nodes, edges, graph.layout());
        var converter = new VisioDiagramService(); var builder = new VisioPackageBuilder();
        assertArrayEquals(builder.build(converter.convert(graph)), builder.build(converter.convert(shuffled)));
        assertArrayEquals(builder.buildBundle(fixtureDocument()), builder.buildBundle(fixtureDocument()));
    }

    @Test void emptyGraphStillProducesAValidEmptyPage() throws Exception {
        byte[] vsdx = new VisioPackageBuilder().build(new VisioDiagramService().convert(
                new DiagramModel("Empty", List.of(), List.of(), new DiagramLayout("LR", false))));
        try (var reader = new XmlVisioDocument(new ByteArrayInputStream(vsdx))) {
            assertEquals(1, reader.getPages().size());
            assertTrue(reader.getPages().iterator().next().getContent().getTopLevelShapes().isEmpty());
        }
    }

    @Test void lossOrderingDoesNotChangeTheImmutableHandoff() throws Exception {
        var document = fixtureDocument();
        document.getLosses().add(new VisioLoss("element", "CP-01", "optional", "OMITTED", "Not retained"));
        byte[] first = new VisioPackageBuilder().buildBundle(document);
        Collections.reverse(document.getLosses());
        assertArrayEquals(first, new VisioPackageBuilder().buildBundle(document));
    }

    @Test
    void bundleBytesDoNotDependOnTheHostTimezone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            byte[] expected = new VisioPackageBuilder().buildBundle(fixtureDocument());
            for (String zone : List.of("Europe/Berlin", "Pacific/Honolulu", "Pacific/Kiritimati")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertArrayEquals(expected, new VisioPackageBuilder().buildBundle(fixtureDocument()), zone);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test void typedNumbersRejectUnboundedExpansionAndNonfiniteValues() {
        for (String invalid : List.of("1e2147483647", "1e-2147483647", "1e9999", "NaN", "9".repeat(351))) {
            assertThrows(IllegalArgumentException.class, () -> new VisioProperty(VisioProperty.Kind.NUMBER, invalid));
        }
        assertEquals("0.5", new VisioProperty(VisioProperty.Kind.NUMBER, "0.5000").value());
        assertEquals(Double.MIN_VALUE, Double.parseDouble(VisioProperty.number(Double.MIN_VALUE).value()));
    }

    @Test void longUnicodeLabelsAndManyParallelConnectorsRemainDistinct() throws Exception {
        String longLabel = "Ä日本語🚀 ".repeat(300);
        List<DiagramEdge> edges = new ArrayList<>();
        for (int i = 0; i < 150; i++) edges.add(new DiagramEdge("e" + i, "a", "b", "CUSTOM/RELATION", .5));
        DiagramModel graph = new DiagramModel("Many", List.of(new DiagramNode("a", longLabel, "Custom category", .5, false, 0),
                new DiagramNode("b", longLabel, "Custom category", .5, false, 1)), edges, new DiagramLayout("LR", false));
        byte[] output = new VisioPackageBuilder().build(new VisioDiagramService().convert(graph));
        Map<String, Map<String, String>> data = shapeData(parts(output).get("visio/pages/page1.xml"));
        assertEquals(152, data.size());
        assertEquals(longLabel, data.get("a").get("label"));
        assertEquals("CUSTOM/RELATION", data.get("e149").get("taxonomy.type"));
    }

    @Test void thousandElementModelPassesPackageAndSchemaValidation() throws Exception {
        List<DiagramNode> nodes = new ArrayList<>(); List<DiagramEdge> edges = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            nodes.add(new DiagramNode("n" + i, "Node " + i, "Capabilities", .5, false, i % 10));
            if (i > 0) edges.add(new DiagramEdge("e" + i, "n" + (i - 1), "n" + i, "SUPPORTS", .5));
        }
        byte[] output = new VisioPackageBuilder().build(new VisioDiagramService().convert(
                new DiagramModel("Large", nodes, edges, new DiagramLayout("LR", false))));
        assertEquals(1999, shapeData(parts(output).get("visio/pages/page1.xml")).size());
    }

    @ParameterizedTest @ValueSource(strings = {"external", "missing", "duplicate", "contentType", "orphan", "pageRef", "shapeRef", "master", "schema", "doctype", "glue", "style"})
    void rejectsCorruptedOpcAndSchemaContracts(String mutation) throws Exception {
        Map<String, String> parts = new HashMap<>(parts(new VisioPackageBuilder().build(fixtureDocument())));
        switch (mutation) {
            case "external" -> parts.compute("_rels/.rels", (k, v) -> v.replace("Target=\"visio/document.xml\"", "TargetMode=\"External\" Target=\"https://invalid.example/file\""));
            case "missing" -> parts.remove("docProps/core.xml");
            case "duplicate" -> parts.compute("_rels/.rels", (k, v) -> v.replace("Id=\"rId2\"", "Id=\"rId1\""));
            case "contentType" -> parts.compute("[Content_Types].xml", (k, v) -> v.replace("application/vnd.ms-visio.page+xml", "text/plain"));
            case "orphan" -> parts.put("orphan.xml", "<unreferenced/>");
            case "pageRef" -> parts.compute("visio/pages/pages.xml", (k, v) -> v.replace("r:id=\"rId1\"", "r:id=\"missing\""));
            case "shapeRef" -> parts.compute("visio/pages/page1.xml", (k, v) -> v.replace("ToSheet=\"1\"", "ToSheet=\"99999\""));
            case "master" -> parts.compute("visio/pages/page1.xml", (k, v) -> v.replace("<Shape ID=", "<Shape Master=\"8\" ID="));
            case "schema" -> parts.compute("visio/pages/page1.xml", (k, v) -> v.replace("<Shapes>", "<WrongShapes>").replace("</Shapes>", "</WrongShapes>"));
            case "doctype" -> parts.put("visio/pages/page1.xml", "<!DOCTYPE bad [<!ENTITY xxe SYSTEM 'file:///not-read'>]><PageContents xmlns='" + NS + "'>&xxe;</PageContents>");
            case "glue" -> parts.compute("visio/pages/page1.xml", (k, v) -> v.replace("FromCell=\"EndX\"", "FromCell=\"BeginX\""));
            case "style" -> parts.compute("visio/pages/page1.xml", (k, v) -> v.replace("LineStyle=\"0\"", "LineStyle=\"987\""));
            default -> fail(mutation);
        }
        assertThrows(IllegalArgumentException.class, () -> VisioOpcValidator.validate(parts));
    }

    @ParameterizedTest @ValueSource(strings = {"\u0000", "\u000b", "\ud800"})
    void rejectsUnsafeIdentityAndMetadataText(String value) {
        VisioDocument document = fixtureDocument();
        document.getPages().get(0).getShapes().get(0).getProperties().put("taxonomy.id", VisioProperty.text(value));
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(document));
    }

    @Test void preservesArbitraryBusinessIdsAsLiteralValues() throws Exception {
        String id = "CP.1/日本語=RUNADDON(\"x\")";
        var graph = new DiagramModel("Safe", List.of(new DiagramNode(id, "Name", "Unknown but declared extension", .5, false, 0)),
                List.of(), new DiagramLayout("LR", false));
        String page = parts(new VisioPackageBuilder().build(new VisioDiagramService().convert(graph))).get("visio/pages/page1.xml");
        assertEquals(Set.of(id), shapeData(page).keySet());
        assertFalse(page.contains("F=\"=RUNADDON"));
    }

    @Test void pageIdsAreValidatedAndRetained() throws Exception {
        var doc = new VisioDocument(); doc.getPages().add(new VisioPage("42", "Page"));
        String pages = parts(new VisioPackageBuilder().build(doc)).get("visio/pages/pages.xml");
        assertTrue(pages.contains("ID=\"42\""));
        doc.getPages().add(new VisioPage("42", "Duplicate"));
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(doc));
    }

    @Test void rejectsOversizedGraphsTextAndPageCountsBeforeOutput() {
        var n = representative().nodes().get(0);
        assertThrows(IllegalArgumentException.class, () -> new VisioDiagramService().convert(
                new DiagramModel("Large", Collections.nCopies(10001, n), List.of(), representative().layout())));
        var invalidMetadata = fixtureDocument(); invalidMetadata.getProperties().put("taxonomy.snapshotId", VisioProperty.text("x".repeat(32768)));
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(invalidMetadata));
        var doc = new VisioDocument(); for (int i = 0; i < 33; i++) doc.getPages().add(new VisioPage("" + i, "Page"));
        VisioDocument tooManyPages = doc;
        assertThrows(IllegalArgumentException.class, () -> new VisioPackageBuilder().build(tooManyPages));
    }

    @Test void unsupportedSelfLoopsAndMissingEndpointsFailWithoutSilentOmission() {
        var graph = representative();
        for (String target : List.of("CP-01", "missing")) assertThrows(IllegalArgumentException.class,
                () -> new VisioDiagramService().convert(new DiagramModel(graph.title(), graph.nodes(),
                        List.of(new DiagramEdge("bad", "CP-01", target, "USES", .5)), graph.layout())));
    }

    @Test void committedFixtureIsReproducible() throws Exception {
        try (var input = getClass().getResourceAsStream("/visio-handoff-v2/representative.visio.zip")) {
            assertNotNull(input);
            assertArrayEquals(input.readAllBytes(), new VisioPackageBuilder().buildBundle(fixtureDocument()));
        }
    }

    private static Map<String, Map<String, String>> shapeData(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Map<String, Map<String, String>> result = new TreeMap<>();
        var shapes = document.getElementsByTagNameNS(NS, "Shape");
        for (int i = 0; i < shapes.getLength(); i++) {
            var shape = (Element) shapes.item(i); Map<String, String> values = new TreeMap<>();
            var rows = shape.getElementsByTagNameNS(NS, "Row");
            for (int r = 0; r < rows.getLength(); r++) {
                var row = (Element) rows.item(r);
                if (!((Element) row.getParentNode()).getAttribute("N").equals("Property")) continue;
                Map<String, String> cells = new HashMap<>(); var elements = row.getElementsByTagNameNS(NS, "Cell");
                for (int c = 0; c < elements.getLength(); c++) {
                    var cell = (Element) elements.item(c); assertFalse(cell.hasAttribute("F"), "Shape Data must contain literal values");
                    cells.put(cell.getAttribute("N"), cell.getAttribute("V"));
                }
                values.put(cells.get("Label"), cells.get("Value"));
            }
            values.put("label", shape.getElementsByTagNameNS(NS, "Text").item(0).getTextContent());
            assertNull(result.put(values.get("taxonomy.id"), values));
        }
        return result;
    }

    static Map<String, byte[]> bytes(byte[] zip) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) assertNull(result.put(entry.getName(), input.readAllBytes()));
        }
        return result;
    }

    static Map<String, String> parts(byte[] zip) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : bytes(zip).entrySet()) result.put(entry.getKey(), new String(entry.getValue(), StandardCharsets.UTF_8));
        return result;
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Path.of(args[0]).getParent());
        Files.write(Path.of(args[0]), new VisioPackageBuilder().buildBundle(fixtureDocument()));
    }
}
