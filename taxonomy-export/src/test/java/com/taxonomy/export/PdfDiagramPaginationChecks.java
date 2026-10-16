package com.taxonomy.export;

import com.taxonomy.diagram.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Real renderer assertions shared by JUnit and the offline diagnostic entry point. */
final class PdfDiagramPaginationChecks {
    static DiagramScene wideScene() {
        var nodes = new ArrayList<DiagramSceneNode>();
        var edges = new ArrayList<DiagramSceneEdge>();
        for (int i = 0; i < 260; i++) {
            nodes.add(node("fixture-node-" + i, 52 + i * 500, 52 + i % 3 * 120));
            if (i > 0) edges.add(edge(nodes.get(i - 1), nodes.get(i), "fixture-edge-" + i));
        }
        return new DiagramScene("Wide editor regression", 130_600, 500, "LR", nodes, edges);
    }
    static void wideCanvasRetainsEveryNodeAndEdge() throws Exception {
        var scene = wideScene();
        byte[] bytes = new ArchitecturePdfRenderer().render(scene, "Saved workspace revision 7");
        try (var document = Loader.loadPDF(bytes)) {
            int diagrams = diagrams(document);
            require(diagrams > 1 && diagrams < 32, "Expected bounded readable diagram panels");
            String diagramText = text(document, 1, diagrams);
            for (var node : scene.nodes()) require(diagramText.contains(node.id() + "\n")
                    || diagramText.contains(node.id() + " "), "Missing diagram identity " + node.id());
            String inventory = text(document, diagrams + 1, document.getNumberOfPages());
            require(inventory.contains("Elements: 260"), "Wrong global element count");
            require(inventory.contains("Relationships: 259"), "Wrong global relationship count");
            for (var edge : scene.edges()) require(inventory.contains(edge.id() + " |"), "Missing edge " + edge.id());
            require(inventory.contains("D1 -> D2"), "Cross-panel relationship needs endpoint page references");
            require(diagramText.contains("Cross-page relationships:"), "No continuation notice");
            require("Saved workspace revision 7".equals(document.getDocumentInformation().getSubject()), "Provenance lost");
            bounds(document, diagrams);
        }
    }
    static void tallCanvasRetainsLastNode() throws Exception {
        var first = node("top-fixture", 52, 52); var last = node("bottom-fixture", 52, 40_052);
        var scene = new DiagramScene("Tall", 760, 40_200, "TB", List.of(first, last), List.of(edge(first, last, "tall-edge")));
        try (var document = Loader.loadPDF(new ArchitecturePdfRenderer().render(scene, "tall"))) {
            require(diagrams(document) == 2, "Empty strips must not create blank pages");
            require(text(document, 1, 2).contains("bottom-fixture"), "Bottom node was lost");
            bounds(document, 2);
        }
    }
    static void smallSceneKeepsItsGeometry() throws Exception {
        var scene = new DiagramScene("Small", 760, 420, "LR", List.of(node("small-fixture", 100, 100)), List.of());
        try (var doc = Loader.loadPDF(new ArchitecturePdfRenderer().render(scene, "small"))) {
            require(diagrams(doc) == 1, "Small scene changed to multiple diagram pages");
            var positions = new ArrayList<TextPosition>();
            var reader = new PDFTextStripper() {
                @Override protected void writeString(String value, List<TextPosition> data) throws IOException {
                    if (value.contains("small-fixture")) positions.addAll(data);
                    super.writeString(value, data);
                }
            };
            reader.setEndPage(1); reader.getText(doc);
            require(!positions.isEmpty() && Math.abs(positions.getFirst().getXDirAdj() - 140) < .01, "Small scene coordinates changed");
        }
    }
    static void giantSingleBoxIsRejected() {
        var huge = new DiagramSceneNode("huge", "too large", "CP", .01, true, 1, 1, false, null, false, 0, 0, 50_000, 84);
        try { new ArchitecturePdfRenderer().render(new DiagramScene("Huge", 50_100, 420, "LR", List.of(huge), List.of()), "huge"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().contains("readable"), expected.getMessage()); return; }
        throw new AssertionError("A single oversized box must not be cropped");
    }
    static void rejectsInvalidGeometryAndBoundsPanelWork() {
        var ordinary = node("fixture", 52, 52);
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1, 1e13}) {
            reject(new DiagramScene("Invalid", invalid, 420, "LR", List.of(ordinary), List.of()));
        }
        reject(new DiagramScene("Duplicate", 760, 420, "LR", List.of(ordinary, ordinary), List.of()));
        reject(new DiagramScene("Outside", 100, 100, "LR", List.of(ordinary), List.of()));
        var other = node("absent", 100, 100);
        reject(new DiagramScene("Missing endpoint", 760, 420, "LR", List.of(ordinary), List.of(edge(ordinary, other, "bad"))));
        var zero = new DiagramSceneNode("zero", "Zero", "CP", 0, false, 1, 1, false, null, false, 0, 0, 0, 82);
        reject(new DiagramScene("Zero dimensions", 760, 420, "LR", List.of(zero), List.of()));
        var many = new ArrayList<DiagramSceneNode>();
        for (int i = 0; i < 257; i++) many.add(node("sparse-" + i, i * 100_000d, 52));
        reject(new DiagramScene("Panel budget", 25_700_000, 420, "LR", many, List.of()));
    }
    private static void reject(DiagramScene scene) {
        try { new ArchitecturePdfRenderer().render(scene, "invalid fixture"); }
        catch (IllegalArgumentException expected) { require(expected.getMessage().contains("readable"), expected.getMessage()); return; }
        throw new AssertionError("Invalid scene accepted: " + scene.title());
    }
    private static DiagramSceneNode node(String id, double x, double y) {
        return new DiagramSceneNode(id, "Recorded node " + id, "Capabilities", .00125, true, 1, 3, false, null, false, x, y, 238, 84);
    }
    private static DiagramSceneEdge edge(DiagramSceneNode from, DiagramSceneNode to, String id) {
        return new DiagramSceneEdge(id, from.id(), to.id(), "SUPPORTS", .5, "trace",
                from.x() + from.width(), from.y() + 42, to.x(), to.y() + 42);
    }
    private static String text(PDDocument doc, int from, int to) throws IOException {
        var reader = new PDFTextStripper(); reader.setStartPage(from); reader.setEndPage(to); return reader.getText(doc);
    }
    private static int diagrams(PDDocument doc) throws IOException {
        for (int p = 1; p <= doc.getNumberOfPages(); p++)
            if (text(doc, p, p).startsWith("Export inventory")) return p - 1;
        throw new AssertionError("Full inventory missing");
    }
    private static void bounds(PDDocument doc, int diagrams) throws IOException {
        for (int page = 1; page <= diagrams; page++) {
            var box = doc.getPage(page - 1).getMediaBox();
            require(box.getWidth() <= 14_400 && box.getHeight() <= 14_400, "PDF page limit exceeded");
            var reader = new PDFTextStripper() {
                @Override protected void writeString(String value, List<TextPosition> data) throws IOException {
                    for (var glyph : data) {
                        require(glyph.getXDirAdj() >= 0 && glyph.getXDirAdj() + glyph.getWidthDirAdj() <= box.getWidth() + .1,
                                "Glyph outside page: " + value);
                        require(glyph.getYDirAdj() >= 0 && glyph.getYDirAdj() <= box.getHeight(), "Glyph outside height");
                        require(glyph.getFontSizeInPt() >= 7, "Unreadably scaled text: " + value);
                    }
                    super.writeString(value, data);
                }
            };
            reader.setStartPage(page);reader.setEndPage(page);reader.getText(doc);
        }
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        wideCanvasRetainsEveryNodeAndEdge(); tallCanvasRetainsLastNode(); smallSceneKeepsItsGeometry(); giantSingleBoxIsRejected(); rejectsInvalidGeometryAndBoundsPanelWork();
        if (args.length == 1) Files.write(Path.of(args[0]), new ArchitecturePdfRenderer().render(wideScene(), "Saved workspace revision 7"));
        System.out.println("PDF_PAGINATION_CHECKS_OK: 5 checks");
    }
}
