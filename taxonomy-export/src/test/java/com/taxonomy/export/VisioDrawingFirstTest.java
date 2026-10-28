package com.taxonomy.export;

import com.taxonomy.diagram.*;
import com.taxonomy.visio.*;
import org.apache.poi.xdgf.usermodel.XmlVisioDocument;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

/** Product-facing navigation contracts, independent of the manifest's text inventory. */
class VisioDrawingFirstTest {
    private static final String NS = "http://schemas.microsoft.com/office/visio/2012/main";

    @Test
    void graphicalPagesPrecedeEveryTextAppendixWithoutLosingGraphOrEvidence() throws Exception {
        var doc = document(14, 10);
        var before = List.copyOf(doc.getPages());
        var ids = before.stream().map(VisioPage::getId).toList();
        var text = appendixText(doc);
        var parts = parts(new VisioPackageBuilder().build(doc));
        boolean appendix = false;
        int drawings = 0;
        for (var page : doc.getPages()) {
            if (page.isEvidencePage()) appendix = true;
            else {
                check(!appendix, "A graphical relationship page is buried after a text appendix");
                drawings++;
            }
        }
        check(drawings > 1, "Fixture must include graphical relationship details");
        check(appendix, "Full reasons and source text must remain available");
        check(new HashSet<>(ids).equals(new HashSet<>(doc.getPages().stream().map(VisioPage::getId).toList())),
                "Page identities must survive reordering");
        check(text.equals(appendixText(doc)), "Appendix content must be retained verbatim");
        var pages = xml(parts.get("visio/pages/pages.xml")).getElementsByTagNameNS(NS, "Page");
        for (int i = 0; i < pages.getLength(); i++) {
            check(((Element) pages.item(i)).getAttribute("ID").equals(doc.getPages().get(i).getId()), "Index order differs");
            check(parts.get("visio/pages/page" + (i + 1) + ".xml").contains(doc.getPages().get(i).getShapes().getFirst().getText().split("\\n")[0]),
                    "Page index must target the matching contents after reorder");
        }
        check(doc.getPages().getFirst().getConnects().size() == 10, "Complete overview relationships must remain");
        try (var reader = new XmlVisioDocument(new ByteArrayInputStream(new VisioPackageBuilder().build(doc)))) {
            var first = reader.getPages().iterator().next();
            check(first.getContent().getConnections().size() == 20, "Independent reader must resolve both ends of every overview connector");
            check(first.getContent().getTopLevelShapes().stream().filter(s -> s.hasMaster()).count() == 24,
                    "Overview must retain native node and connector masters");
        }
    }

    @Test
    void openingWindowSelectsAndFramesDrawingRatherThanItsLargeContextText() throws Exception {
        var doc = document(3, 2);
        var parts = parts(new VisioPackageBuilder().build(doc));
        var windows = xml(parts.get("visio/windows.xml")).getElementsByTagNameNS(NS, "Window");
        check(windows.getLength() == 1, "Expected an explicit graphical opening window, not an empty Windows part");
        var window = (Element) windows.item(0);
        check("Drawing".equals(window.getAttribute("WindowType")), "Opening window must be a drawing");
        check("Page".equals(window.getAttribute("ContainerType")), "Window must select a page");
        check(doc.getPages().getFirst().getId().equals(window.getAttribute("Page")), "Opening page must be the overview");
        check(window.getAttribute("Page").equals(window.getAttribute("Container")), "Window container and page must agree");
        double x = Double.parseDouble(window.getAttribute("ViewCenterX"));
        double y = Double.parseDouble(window.getAttribute("ViewCenterY"));
        double scale = Double.parseDouble(window.getAttribute("ViewScale"));
        check(Double.isFinite(x + y + scale) && scale > 0 && scale <= 1, "Viewport must be finite and useful");
        var nodes = doc.getPages().getFirst().getShapes().stream().filter(s -> s.getProperties().containsKey("taxonomy.id")).toList();
        double minY = nodes.stream().mapToDouble(s -> s.getY() - s.getHeight() / 2).min().orElseThrow();
        double maxY = nodes.stream().mapToDouble(s -> s.getY() + s.getHeight() / 2).max().orElseThrow();
        for (var connector : doc.getPages().getFirst().getConnects()) {
            var box = connector.getTextBox();
            if (box == null || box.text().isBlank()) continue;
            minY = Math.min(minY, box.y() - box.height() / 2);
            maxY = Math.max(maxY, box.y() + box.height() / 2);
        }
        check(y >= minY && y <= maxY, "Graph and its captions, not the metadata text block, must be centered initially");
        for (var node : nodes) {
            check(Math.abs(node.getX() - x) + node.getWidth() / 2 <= 10 / scale / 2,
                    "Initial viewport must include the entire drawing horizontally");
            check(Math.abs(node.getY() - y) + node.getHeight() / 2 <= 7.5 / scale / 2,
                    "Initial viewport must include the entire drawing vertically");
        }
    }

    @Test
    void openingPageUsesActualIdAndNotItsPositionOrATextPage() throws Exception {
        var doc = new VisioDocument();
        var text = new VisioPage("7", "Evidence appendix"); text.setEvidencePage(true);
        var drawing = new VisioPage("42", "Drawing & source");
        drawing.getShapes().add(new VisioShape("1", "A", 3, 2, 2, 1, "UserApplication", false));
        doc.getPages().add(text); doc.getPages().add(drawing);
        var window = (Element) xml(parts(new VisioPackageBuilder().build(doc)).get("visio/windows.xml"))
                .getElementsByTagNameNS(NS, "Window").item(0);
        check(window != null && "42".equals(window.getAttribute("Page")), "Must use the actual drawing page ID");
        check(doc.getPages().getFirst() == drawing, "Text appendix must not be first");
    }

    @Test
    void emptyDocumentPageStillHasAFiniteOpeningView() throws Exception {
        var doc = new VisioDocument(); doc.getPages().add(new VisioPage("19", "Empty"));
        var part = parts(new VisioPackageBuilder().build(doc)).get("visio/windows.xml");
        var window = (Element) xml(part).getElementsByTagNameNS(NS, "Window").item(0);
        check(window != null && "19".equals(window.getAttribute("Page")), "Empty graph must still open its existing page");
        check(Double.isFinite(Double.parseDouble(window.getAttribute("ViewScale"))), "Empty graph zoom must be finite");
    }

    @Test
    void bytePruningRemovesOnlyOptionalDrawingsBeforeMandatoryAppendices() throws Exception {
        var doc = document(100, 12);
        var builder = new VisioPackageBuilder();
        builder.build(doc); // Also exercises a second build of an already reordered document.
        var text = appendixText(doc);
        int original = doc.getPages().size();
        for (var shape : doc.getPages().getFirst().getShapes()) {
            if (!shape.getProperties().containsKey("taxonomy.id")) continue;
            for (int i = 0; i < 6; i++) shape.getProperties().put("taxonomy.padding" + i, VisioProperty.text(""));
        }
        var base = new VisioDocument();
        doc.getPages().stream().filter(p -> !p.isRelationshipDetail()).forEach(base.getPages()::add);
        base.getProperties().putAll(doc.getProperties()); base.getLosses().addAll(doc.getLosses());
        VisioPresentation.updateDetailCoverage(base, "byte capacity");
        long size = bytes(parts(builder.build(base)));
        long remaining = (VisioHandoffProfile.MAX_XML_BYTES - size - 2048) / 2;
        for (var shape : doc.getPages().getFirst().getShapes()) {
            if (!shape.getProperties().containsKey("taxonomy.id")) continue;
            for (int i = 0; i < 6; i++) {
                int n = (int) Math.min(remaining, 32767);
                shape.getProperties().put("taxonomy.padding" + i, VisioProperty.text("x".repeat(n)));
                remaining -= n;
            }
        }
        check(remaining == 0, "Padding must fit supported property limits");
        check(bytes(parts(builder.build(base))) <= VisioHandoffProfile.MAX_XML_BYTES, "Mandatory base must fit independently");
        var parts = parts(builder.build(doc));
        check(doc.getPages().size() < original, "Optional drawing pages must actually be pruned");
        check(text.equals(appendixText(doc)), "Mandatory source/evidence pages must not be pruned");
        check(doc.getPages().getFirst().getConnects().size() == 12, "Overview must remain complete");
        check(bytes(parts) <= VisioHandoffProfile.MAX_XML_BYTES, "Bounded package must fit");
        var pageIndex = xml(parts.get("visio/pages/pages.xml")).getElementsByTagNameNS(NS, "Page");
        check(pageIndex.getLength() == doc.getPages().size(), "No stale page index after pruning");
        for (int i = 0; i < doc.getPages().size(); i++) {
            var page = doc.getPages().get(i);
            check(parts.get("visio/pages/page" + (i + 1) + ".xml").contains(page.getShapes().getFirst().getText().split("\\n")[0]),
                    "Cached contents must follow stable page identity, not old ordinal");
        }
    }

    private static VisioDocument document(int nodeCount, int edgeCount) {
        var nodes = new ArrayList<DiagramNode>(); var edges = new ArrayList<DiagramEdge>();
        for (int i = 0; i < nodeCount; i++) nodes.add(new DiagramNode("n" + i, "Application " + i, "UserApplication", .5, false, i % 3));
        for (int i = 0; i < edgeCount; i++) edges.add(new DiagramEdge("r" + i, "n" + (i % nodeCount), "n" + ((i + 1) % nodeCount), "USES", .5));
        return new VisioDiagramService().convert(new DiagramModel("Architecture drawing", nodes, edges, new DiagramLayout("LR", false)),
                new VisioExportMetadata(Map.of("taxonomy.requirementText", VisioProperty.text("Original requirement stays complete."),
                        "taxonomy.generatorName", VisioProperty.text("Taxonomy Architecture Analyzer"),
                        "taxonomy.exportedBy", VisioProperty.text("Exporter")),
                        Map.of("n0", Map.of("taxonomy.reason", VisioProperty.text("Full saved reason remains available."))), Map.of(), List.of()));
    }

    private static Map<String, List<String>> appendixText(VisioDocument doc) {
        var result = new TreeMap<String, List<String>>();
        doc.getPages().stream().filter(VisioPage::isEvidencePage).forEach(p -> result.put(p.getId(), p.getShapes().stream().map(VisioShape::getText).toList()));
        return result;
    }

    private static Map<String, String> parts(byte[] bytes) throws Exception {
        var parts = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        return parts;
    }

    private static long bytes(Map<String, String> parts) {
        return parts.values().stream().mapToLong(s -> s.getBytes(StandardCharsets.UTF_8).length).sum();
    }

    private static org.w3c.dom.Document xml(String text) throws Exception {
        var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
