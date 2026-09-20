package com.taxonomy.export;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.diagram.*;
import com.taxonomy.visio.*;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VisioGraphicalQualityTest {
    @Test void reverseVerticalAndCoincidentCaptionsAreUprightFiniteAndDoNotCoverNodesOrEachOther() throws Exception {
        var pairs = graph(2, 6);
        var vertical = new DiagramModel("Vertical multiedges", pairs.nodes().stream().map(n ->
                new DiagramNode(n.id(), n.label(), n.type(), n.relevance(), false, 0)).toList(), pairs.edges(), pairs.layout());
        assertGeometry(new VisioDiagramService().convert(graph(6, 12)));
        assertGeometry(new VisioDiagramService().convert(vertical));
    }

    private static void assertGeometry(VisioDocument doc) throws Exception {
        var parts = VisioHandoffContractTest.parts(new VisioPackageBuilder().build(doc));
        for (int p = 0; p < doc.getPages().size(); p++) {
            var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(
                    parts.get("visio/pages/page" + (p + 1) + ".xml").getBytes(StandardCharsets.UTF_8)));
            var shapes = xml.getElementsByTagName("Shape");
            boolean seenNode = false;
            List<double[]> captions = new ArrayList<>();
            for (int i = 0; i < shapes.getLength(); i++) {
                var s = (Element) shapes.item(i);
                if (!hasCell(s, "OneD")) { seenNode = true; continue; }
                assertFalse(seenNode, "connector strokes must be behind opaque nodes");
                double angle = value(s, "Angle"), textAngle = value(s, "TxtAngle");
                assertEquals(0, angle + textAngle, 1e-10, "page text stays upright including reverse edges");
                assertEquals("-Angle", cell(s, "TxtAngle").getAttribute("F"));
                double w = value(s, "TxtWidth"), h = value(s, "TxtHeight");
                assertTrue(w > 0 && h > 0 && Double.isFinite(w + h));
                assertEquals(w / 2, value(s, "TxtLocPinX"), 1e-10);
                assertEquals(h / 2, value(s, "TxtLocPinY"), 1e-10);
                double dx = value(s, "TxtPinX") - value(s, "LocPinX"), dy = value(s, "TxtPinY");
                double x = value(s, "PinX") + dx * Math.cos(angle) - dy * Math.sin(angle);
                double y = value(s, "PinY") + dx * Math.sin(angle) + dy * Math.cos(angle);
                if (s.getElementsByTagName("Text").item(0).getTextContent().isBlank()) continue;
                double[] box = {x, y, w, h};
                for (var node : doc.getPages().get(p).getShapes()) assertFalse(overlap(box,
                        new double[]{node.getX(), node.getY(), node.getWidth(), node.getHeight()}), "caption covers node");
                for (var earlier : captions) assertFalse(overlap(box, earlier), "captions collide");
                captions.add(box);
            }
        }
    }

    @Test void denseDetailsCoverEveryCanonicalRelationshipOnceWithStableKeysAndUniqueEndpoints() throws Exception {
        var graph = graph(38, 44);
        var doc = new VisioDiagramService().convert(graph);
        assertTrue(doc.getPages().size() > 1, "dense overview needs native details");
        Set<String> covered = new HashSet<>();
        for (var page : doc.getPages().subList(1, doc.getPages().size())) {
            assertTrue(page.getShapes().size() <= 6);
            assertTrue(page.getConnects().size() <= 6);
            assertEquals(page.getShapes().size(), page.getShapes().stream().map(s -> s.getProperties().get("taxonomy.id")).distinct().count());
            for (var c : page.getConnects()) {
                assertTrue(covered.add(c.getProperties().get("taxonomy.id").value()));
                assertNotNull(c.getProperties().get("taxonomy.displayKey"));
                assertEquals("USES", c.getProperties().get("taxonomy.type").value());
                var source = page.getShapes().stream().filter(n -> n.getId().equals(c.getFromShape())).findFirst().orElseThrow();
                var target = page.getShapes().stream().filter(n -> n.getId().equals(c.getToShape())).findFirst().orElseThrow();
                assertEquals(c.getProperties().get("taxonomy.sourceId"), source.getProperties().get("taxonomy.id"));
                assertEquals(c.getProperties().get("taxonomy.targetId"), target.getProperties().get("taxonomy.id"));
                assertTrue(c.getTextBox().text().contains(source.getProperties().get("taxonomy.displayKey").value() + " → "
                        + target.getProperties().get("taxonomy.displayKey").value()));
            }
        }
        assertEquals(new HashSet<>(graph.edges().stream().map(DiagramEdge::id).toList()), covered);
        var reversedNodes = new ArrayList<>(graph.nodes()); Collections.reverse(reversedNodes);
        var reversedEdges = new ArrayList<>(graph.edges()); Collections.reverse(reversedEdges);
        assertArrayEquals(new VisioPackageBuilder().build(doc), new VisioPackageBuilder().build(new VisioDiagramService().convert(
                new DiagramModel(graph.title(), reversedNodes, reversedEdges, graph.layout()))));
    }

    @Test void largeValidOverviewSurvivesOptionalDetailCapacityExhaustion() {
        var doc = new VisioDiagramService().convert(graph(10_000, 30_000));
        assertEquals(10_000, doc.getPages().getFirst().getShapes().size());
        assertEquals(30_000, doc.getPages().getFirst().getConnects().size());
        assertTrue(doc.getPages().size() <= 32);
        assertTrue(doc.getPages().stream().mapToInt(p -> p.getShapes().size()).sum() <= 20_000);
        assertTrue(doc.getPages().stream().mapToInt(p -> p.getConnects().size()).sum() <= 60_000);
        assertTrue(doc.getLosses().stream().anyMatch(l -> l.field().equals("relationshipDetailCoverage") && l.kind().equals("TRUNCATED")));
        VisioPackageValidator.validate(doc);
    }

    @Test void overviewAndImpactReserveShapeAndConnectorCapacityBeforeDetails() {
        var base = graph(10_000, 30_000);
        var selected = base.nodes().stream().map(n -> new DiagramNode(n.id(), n.label(), n.type(), n.relevance(),
                false, n.layer(), 0, !n.id().equals("n09999"), null, false)).toList();
        var doc = new VisioDiagramService().convert(new DiagramModel(base.title(), selected, base.edges(), base.layout()));
        assertEquals(2, doc.getPages().size());
        assertEquals(19_999, doc.getPages().stream().mapToInt(p -> p.getShapes().size()).sum());
        assertEquals(59_994, doc.getPages().stream().mapToInt(p -> p.getConnects().size()).sum());
        assertEquals("0", doc.getProperties().get("taxonomy.detailRelationshipsCovered").value());
        VisioPackageValidator.validate(doc);
    }

    @Test void optionalDetailsCannotPushAValidBasePackageOverTheByteLimit() throws Exception {
        var doc = new VisioDiagramService().convert(graph(100, 7));
        for (var page : doc.getPages()) for (var shape : page.getShapes())
            for (int i = 0; i < 5; i++) shape.getProperties().put("taxonomy.padding" + i, VisioProperty.text("x".repeat(32000)));
        byte[] bytes = new VisioPackageBuilder().build(doc);
        var parts = VisioHandoffContractTest.bytes(bytes);
        assertTrue(parts.values().stream().mapToLong(p -> p.length).sum() <= 33554432);
        assertEquals(100, doc.getPages().getFirst().getShapes().size());
        assertEquals(7, doc.getPages().getFirst().getConnects().size());
        assertTrue(Integer.parseInt(doc.getProperties().get("taxonomy.detailRelationshipsCovered").value()) < 7);
        assertTrue(doc.getLosses().stream().anyMatch(l -> l.field().equals("relationshipDetailCoverage") && l.rationale().contains("byte")));
    }

    @Test void pageLimitIsExactAndTheRetainedCoverageMatchesTheActualNativeDetails() {
        var doc = new VisioDiagramService().convert(graph(2, 200));
        assertEquals(32, doc.getPages().size());
        assertEquals(186, doc.getPages().subList(1, 32).stream().mapToInt(p -> p.getConnects().size()).sum());
        assertEquals("186", doc.getProperties().get("taxonomy.detailRelationshipsCovered").value());
        assertEquals("200", doc.getProperties().get("taxonomy.detailRelationshipsTotal").value());
    }

    @Test void byteTrimmingReportsOnlyRetainedDetailCaptionLossesAndPreservesSourceLosses() throws Exception {
        assertDetailLossesAtByteCapacity(true);
    }

    @Test void removedDetailLossesCannotRejectAnIndependentlyValidBaseAtTheByteLimit() throws Exception {
        assertDetailLossesAtByteCapacity(false);
    }

    private static void assertDetailLossesAtByteCapacity(boolean retainDetails) throws Exception {
        String detailRationale = "Detail type shortened; full type retained in taxonomy.type on native connector.";
        String longType = "ÜRELATION_".repeat(50);
        var graph = graph(100, 12);
        var nodes = new ArrayList<>(graph.nodes());
        nodes.set(0, new DiagramNode("n00000", "Long label ".repeat(50), "Capability", .5, false, 0));
        var edges = graph.edges().stream()
                .map(e -> new DiagramEdge(e.id(), e.sourceId(), e.targetId(), longType, .5)).toList();
        var sourceLoss = new VisioLoss("relationship", "r00011", "displayType", "TRUNCATED", "Source-side type conversion loss.");
        var metadata = new VisioExportMetadata(Map.of(), Map.of(), Map.of(), List.of(sourceLoss));
        var doc = new VisioDiagramService().convert(
                new DiagramModel(graph.title(), nodes, edges, graph.layout()), metadata);
        int originalPages = doc.getPages().size();
        assertTrue(originalPages > 2);
        for (var page : doc.getPages()) {
            if (!page.isRelationshipDetail()) continue;
            for (var connector : page.getConnects()) {
                assertEquals("TYPE_TRUNCATED", connector.getProperties().get("taxonomy.captionDisposition").value());
            }
        }

        // Padding lives only on the complete overview, never on optional detail copies.
        for (var shape : doc.getPages().getFirst().getShapes()) {
            for (int i = 0; i < 6; i++) {
                shape.getProperties().put("taxonomy.padding" + i, VisioProperty.text(""));
            }
        }
        // Independently package the same base with its legitimate losses. Deliberately
        // exclude only the exact generated detail-caption records from this oracle.
        var base = new VisioDocument();
        base.getPages().add(doc.getPages().getFirst());
        base.getProperties().putAll(doc.getProperties());
        doc.getLosses().stream().filter(l -> !l.rationale().equals(detailRationale)).forEach(base.getLosses()::add);
        VisioPresentation.updateDetailCoverage(base, "byte capacity");
        var builder = new VisioPackageBuilder();
        var baseParts = VisioHandoffContractTest.bytes(builder.build(base));
        var fullParts = VisioHandoffContractTest.bytes(builder.build(doc));
        long baseBytes = packageBytes(baseParts);
        long addedBytes = retainDetails
                ? VisioHandoffProfile.MAX_XML_BYTES - packageBytes(fullParts)
                        + fullParts.get("visio/pages/page" + originalPages + ".xml").length / 2
                : VisioHandoffProfile.MAX_XML_BYTES - baseBytes - 64;
        long characters = addedBytes / 2; // ASCII value occurs once in XML and once in JSON.
        for (var shape : base.getPages().getFirst().getShapes()) {
            for (int i = 0; i < 6; i++) {
                int length = (int) Math.min(characters, 32767);
                shape.getProperties().put("taxonomy.padding" + i, VisioProperty.text("x".repeat(length)));
                characters -= length;
            }
        }
        assertEquals(0, characters, "padding stays within supported per-property limits");
        long paddedBaseBytes = packageBytes(VisioHandoffContractTest.bytes(builder.build(base)));
        assertEquals(baseBytes + 2 * (addedBytes / 2), paddedBaseBytes);
        assertTrue(paddedBaseBytes <= VisioHandoffProfile.MAX_XML_BYTES, "independent complete base must serialize");
        if (!retainDetails) assertTrue(VisioHandoffProfile.MAX_XML_BYTES - paddedBaseBytes <= 65);

        var parts = VisioHandoffContractTest.bytes(builder.build(doc));
        assertTrue(packageBytes(parts) <= VisioHandoffProfile.MAX_XML_BYTES);
        assertTrue(doc.getPages().size() < originalPages, "actual builder must trim optional pages");
        if (retainDetails) assertEquals(originalPages - 1, doc.getPages().size());
        else assertEquals(1, doc.getPages().size());
        assertEquals(100, doc.getPages().getFirst().getShapes().size());
        assertEquals(12, doc.getPages().getFirst().getConnects().size());
        doc.getPages().getFirst().getConnects()
                .forEach(c -> assertEquals(longType, c.getProperties().get("taxonomy.type").value()));

        var json = JsonMapper.builder().build();
        var manifest = json.readTree(parts.get("taxonomy/manifest.json"));
        Set<String> retained = new TreeSet<>();
        doc.getPages().stream().filter(VisioPage::isRelationshipDetail).forEach(p -> p.getConnects()
                .forEach(c -> retained.add(c.getProperties().get("taxonomy.id").value())));
        Set<String> reported = new TreeSet<>();
        int sourceLosses = 0, baseLabelLosses = 0, detailLosses = 0;
        for (var loss : manifest.path("losses")) {
            if (loss.path("rationale").asText().equals(detailRationale)) {
                reported.add(loss.path("id").asText());
                assertEquals("displayType", loss.path("field").asText());
                assertEquals("TRUNCATED", loss.path("kind").asText());
                detailLosses++;
            }
            if (loss.equals(json.valueToTree(sourceLoss))) sourceLosses++;
            if (loss.path("id").asText().equals("n00000") && loss.path("field").asText().equals("displayLabel")) {
                baseLabelLosses++;
            }
        }
        assertEquals(retained, reported, "removed detail captions must not contribute generated loss records");
        assertEquals(retained.size(), detailLosses, "one loss per retained truncated caption");
        assertEquals(1, sourceLosses, "same-field source loss for a removed detail relationship survives");
        assertEquals(1, baseLabelLosses, "genuine overview display loss survives");
        assertTrue(doc.getLosses().contains(sourceLoss));
        assertEquals(Integer.toString(retained.size()), manifest.path("authority")
                .path("taxonomy.detailRelationshipsCovered").path("value").asText());
        assertEquals("12", manifest.path("authority")
                .path("taxonomy.detailRelationshipsTotal").path("value").asText());
        assertTrue(doc.getLosses().stream().anyMatch(l -> l.field().equals("relationshipDetailCoverage")
                && l.kind().equals("TRUNCATED") && l.rationale().contains("byte capacity")));
        assertEquals(doc.getPages().size(), manifest.path("pages").size());
        int manifestDetailCount = 0;
        for (int i = 1; i < manifest.path("pages").size(); i++) {
            manifestDetailCount += manifest.path("pages").get(i).path("relationships").size();
        }
        assertEquals(retained.size(), manifestDetailCount);
        var pageIndex = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(parts.get("visio/pages/pages.xml")));
        assertEquals(doc.getPages().size(), pageIndex.getElementsByTagName("Page").getLength());
        assertEquals(doc.getPages().size(), parts.keySet().stream()
                .filter(p -> p.matches("visio/pages/page[0-9]+\\.xml")).count());
        System.out.printf("Byte/loss regression: retained=%d/12, pages=%d/%d, baseBytes=%d, packageBytes=%d, cap=%d%n",
                retained.size(), doc.getPages().size(), originalPages, paddedBaseBytes, packageBytes(parts), VisioHandoffProfile.MAX_XML_BYTES);
    }

    private static long packageBytes(Map<String, byte[]> parts) {
        return parts.values().stream().mapToLong(p -> p.length).sum();
    }

    @Test void sparseLongTypesTriggerExplicitLossAndReadableDetailsRatherThanSilentTruncation() {
        var base = graph(2, 1);
        String type = "RELATION_".repeat(12);
        var edge = new DiagramEdge("r00000", "n00000", "n00001", type, .5);
        var doc = new VisioDiagramService().convert(new DiagramModel(base.title(), base.nodes(), List.of(edge), base.layout()));
        assertTrue(doc.getPages().size() > 1);
        assertTrue(doc.getLosses().stream().anyMatch(l -> l.id().equals("r00000") && l.field().equals("overviewType") && l.kind().equals("TRUNCATED")));
        assertEquals(type, doc.getPages().getLast().getConnects().getFirst().getTextBox().text()
                .substring(doc.getPages().getLast().getConnects().getFirst().getTextBox().text().indexOf('\n') + 1).replace("\n", ""));
        assertEquals("READABLE_DETAIL", doc.getPages().getLast().getConnects().getFirst().getProperties().get("taxonomy.captionDisposition").value());
    }

    @Test void wideGlyphLabelsWrapWithinTheFixedReadableNodeBox() {
        var base = graph(2, 1);
        var node = new DiagramNode("n00000", "W".repeat(100), "Capability", .5, false, 0);
        var doc = new VisioDiagramService().convert(new DiagramModel(base.title(), List.of(node, base.nodes().get(1)), base.edges(), base.layout()));
        for (String line : doc.getPages().getFirst().getShapes().getFirst().getText().split("\n"))
            assertTrue(line.replace("…", "").length() <= 12, "Wide Latin glyphs must fit the 2-inch box at 10pt");
    }

    @Test void unicodeDisplayTruncationRetainsFullCanonicalLabelAndReportsLoss() throws Exception {
        String label = "Ü日本語🚀".repeat(100);
        var base = graph(2, 1);
        var graph = new DiagramModel("Unicode", List.of(new DiagramNode("n00000", label, "Capability", .5, false, 0), base.nodes().get(1)), base.edges(), base.layout());
        var doc = new VisioDiagramService().convert(graph);
        var node = doc.getPages().getFirst().getShapes().getFirst();
        assertTrue(node.getText().length() < label.length());
        assertEquals(label, node.getProperties().get("taxonomy.label").value());
        assertTrue(doc.getLosses().stream().anyMatch(l -> l.id().equals("n00000") && l.kind().equals("TRUNCATED")));
        assertDoesNotThrow(() -> new VisioPackageBuilder().build(doc));
    }

    private static DiagramModel graph(int nodes, int edges) {
        List<DiagramNode> ns = new ArrayList<>(); List<DiagramEdge> es = new ArrayList<>();
        for (int i = 0; i < nodes; i++) ns.add(new DiagramNode(String.format("n%05d", i), "Node " + i, "Capability", .5, false, i % 3));
        for (int i = 0; i < edges; i++) es.add(new DiagramEdge(String.format("r%05d", i), String.format("n%05d", i % nodes),
                String.format("n%05d", (i + 1) % nodes), "USES", .5));
        return new DiagramModel("Quality", ns, es, new DiagramLayout("LR", false));
    }
    private static boolean overlap(double[] a, double[] b) { return Math.abs(a[0]-b[0]) < (a[2]+b[2])/2 - 1e-8 && Math.abs(a[1]-b[1]) < (a[3]+b[3])/2 - 1e-8; }
    private static boolean hasCell(Element s, String name) { return cell(s, name) != null; }
    private static Element cell(Element s, String name) { for (var n = s.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && e.getTagName().equals("Cell") && e.getAttribute("N").equals(name)) return e; return null; }
    private static double value(Element s, String name) { var c = cell(s, name); assertNotNull(c, "Missing " + name); return Double.parseDouble(c.getAttribute("V")); }
}
