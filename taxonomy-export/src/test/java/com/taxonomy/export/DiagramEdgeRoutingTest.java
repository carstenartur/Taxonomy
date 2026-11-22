package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagramEdgeRoutingTest {
    @Test void retainedA1SvgShowsTheDirectedConnectorInFrontOfNoCard() throws Exception {
        var scene = a1(0, 1458);
        var svg = new SvgDiagramRenderer().render(scene);
        var paths = svgPaths(svg);
        assertDirectedClear(paths.getFirst(), scene, scene.edges().getFirst());
        assertThat(svg).contains("data-node-id=\"CI-1047\" transform=\"translate(906.00,400.00)\"", "marker-end=");
    }

    @Test void singlePdfPanelRoutesA1GeometryAroundTheIntermediateCard() throws Exception {
        // Translate the same three cards into one readable A3 panel.
        var scene = a1(524, 934);
        var edge = scene.edges().getFirst();
        try (var pdf = Loader.loadPDF(new ArchitecturePdfRenderer().render(scene, "Retained A1 geometry"))) {
            double originX = PdfDiagramPages.PAGE_MARGIN;
            double originY = PdfDiagramPages.PAGE_HEIGHT - PdfDiagramPages.PAGE_MARGIN - PdfDiagramPages.HEADER_HEIGHT;
            var tokens = new PDFStreamParser(pdf.getPage(0)).parse();
            var path = new ArrayList<Point2D.Double>();
            boolean cleared = false;
            for (int i = 2; i < tokens.size(); i++) {
                if (!(tokens.get(i) instanceof Operator operation)) continue;
                if ("S".equals(operation.getName()) && !path.isEmpty()) {
                    assertDirectedClear(path, scene, edge);
                    cleared = true;
                    break;
                }
                if (!"m".equals(operation.getName()) && !"l".equals(operation.getName())) continue;
                double x = ((COSNumber) tokens.get(i - 2)).floatValue() - originX;
                double y = originY - ((COSNumber) tokens.get(i - 1)).floatValue();
                if ("m".equals(operation.getName()) && Math.abs(x - edge.sourceX()) < .01
                        && Math.abs(y - edge.sourceY()) < .01 || "l".equals(operation.getName()) && !path.isEmpty())
                    path.add(new Point2D.Double(x, y));
            }
            assertThat(cleared).as("directed stroked connector on the first panel").isTrue();
        }
    }

    @Test void safeStraightSvgRetainsItsOriginalPortsAndLine() {
        var source = card("source", 52, 52);
        var target = card("target", 382, 52);
        var edge = new DiagramSceneEdge("clear", source.id(), target.id(), "SUPPORTS", .7, "impact", 290, 93, 382, 93);
        var scene = new DiagramScene("Clear", 760, 420, "LR", List.of(source, target), List.of(edge));
        assertThat(new SvgDiagramRenderer().render(scene))
                .contains("<line x1=\"290.00\" y1=\"93.00\" x2=\"382.00\" y2=\"93.00\"");
    }

    @Test void verticalLoopAndParallelSvgLinksRetainTheirPortsAndAvoidTheCards() throws Exception {
        var top = card("top", 52, 52);
        var middle = card("middle", 52, 168);
        var bottom = card("bottom", 52, 284);
        var edges = List.of(
                new DiagramSceneEdge("vertical", "top", "bottom", "SUPPORTS", .7, "impact", 290, 93, 52, 325),
                new DiagramSceneEdge("parallel", "top", "bottom", "SUPPORTS", .7, "impact", 290, 93, 52, 325),
                new DiagramSceneEdge("loop", "middle", "middle", "SUPPORTS", .7, "impact", 290, 209, 52, 209));
        var scene = new DiagramScene("Stacked", 760, 420, "LR", List.of(top, middle, bottom), edges);
        var paths = svgPaths(new SvgDiagramRenderer().render(scene));
        for (int i = 0; i < paths.size(); i++) assertDirectedClear(paths.get(i), scene, edges.get(i));
        assertThat(paths.getFirst()).isNotEqualTo(paths.get(1));
    }

    @Test void adjacentDetourCaptionsDoNotOverprintAndAllRelationsKeepTheirInventoryEntries() throws Exception {
        var original = a1(0, 1458);
        var edges = List.of(
                new DiagramSceneEdge("e1", "CI-1151", "BP-2125", "SUPPORTS", .7, "impact", 1168, 441, 814, 441),
                new DiagramSceneEdge("e2", "CI-1151", "BP-2125", "USES", .7, "impact", 1168, 441, 814, 441),
                new DiagramSceneEdge("e3", "CI-1151", "BP-2125", "REQUIRES", .7, "impact", 1168, 441, 814, 441));
        var scene = new DiagramScene(original.title(), original.width(), original.height(), "LR", original.nodes(), edges);
        var svg = new SvgDiagramRenderer().render(scene);
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        var texts = doc.getElementsByTagName("text");
        var boxes = new ArrayList<Rectangle2D>();
        var font = new Font("Arial", Font.PLAIN, 11);
        var fontContext = new FontRenderContext(null, true, true);
        for (int i = 0; i < texts.getLength(); i++) {
            var text = (Element) texts.item(i);
            if (!(text.getParentNode() instanceof Element parent) || !"architecture-edge".equals(parent.getAttribute("class"))) continue;
            var measured = font.getStringBounds(text.getTextContent(), fontContext);
            double x = Double.parseDouble(text.getAttribute("x")), y = Double.parseDouble(text.getAttribute("y"));
            var box = new Rectangle2D.Double(x - measured.getWidth() / 2, y + measured.getY(),
                    measured.getWidth(), measured.getHeight());
            assertThat(boxes).noneMatch(box::intersects);
            boxes.add(box);
        }
        assertThat(boxes).isNotEmpty();
        assertThat(svgPaths(svg)).hasSize(3);
        assertThat(svg).contains("e1 | CI-1151", "e2 | CI-1151", "e3 | CI-1151");
    }

    @Test void fullRelevanceDetourArrowFitsItsApproachInTheTwentyFourUnitGutter() throws Exception {
        var target = card("target", 52, 168);
        var adjacent = card("adjacent", 314, 168);
        var source = card("source", 576, 168);
        var edge = new DiagramSceneEdge("e1", source.id(), target.id(), "SUPPORTS", 1, "impact", 576, 209, 290, 209);
        var scene = new DiagramScene("Narrow gutter", 866, 420, "LR", List.of(source, adjacent, target), List.of(edge));
        String svg = new SvgDiagramRenderer().render(scene);
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        var polyline = (Element) doc.getElementsByTagName("polyline").item(0);
        var markers = doc.getElementsByTagName("marker");
        String id = polyline.getAttribute("marker-end").replace("url(#", "").replace(")", "");
        Element marker = null;
        for (int i = 0; i < markers.getLength(); i++) {
            var candidate = (Element) markers.item(i);
            if (id.equals(candidate.getAttribute("id"))) marker = candidate;
        }
        assertThat(marker).isNotNull();
        double scale = "userSpaceOnUse".equals(marker.getAttribute("markerUnits")) ? 1
                : Double.parseDouble(polyline.getAttribute("stroke-width"));
        double arrowExtent = Double.parseDouble(marker.getAttribute("refX")) * scale;
        var path = svgPaths(svg).getFirst();
        double approachLength = path.getLast().distance(path.get(path.size() - 2));
        assertThat(arrowExtent).as("arrow head stays in the final route leg").isLessThanOrEqualTo(approachLength);
    }

    private static DiagramScene a1(double offsetX, double width) {
        var source = card("CI-1151", 1168 - offsetX, 400);
        var target = card("BP-2125", 576 - offsetX, 400);
        var intermediate = card("CI-1047", 906 - offsetX, 400);
        var edge = new DiagramSceneEdge("e1", source.id(), target.id(), "SUPPORTS", .7, "impact",
                1168 - offsetX, 441, 814 - offsetX, 441);
        return new DiagramScene("Retained A1 geometry", width, 650, "LR",
                List.of(source, target, intermediate), List.of(edge));
    }

    private static DiagramSceneNode card(String id, double x, double y) {
        return new DiagramSceneNode(id, id, "COI Services", .7, false, 2, 0,
                false, null, false, x, y, 238, 82);
    }

    private static List<List<Point2D.Double>> svgPaths(String svg) throws Exception {
        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
        var groups = doc.getElementsByTagName("g");
        var result = new ArrayList<List<Point2D.Double>>();
        for (int i = 0; i < groups.getLength(); i++) {
            var group = (Element) groups.item(i);
            if (!"architecture-edge".equals(group.getAttribute("class"))) continue;
            var polyline = (Element) group.getElementsByTagName("polyline").item(0);
            if (polyline != null) {
                assertThat(polyline.getAttribute("marker-end")).startsWith("url(#arrow-");
                result.add(Arrays.stream(polyline.getAttribute("points").split(" ")).map(pair -> {
                    var values = pair.split(",");
                    return new Point2D.Double(Double.parseDouble(values[0]), Double.parseDouble(values[1]));
                }).toList());
            } else {
                var line = (Element) group.getElementsByTagName("line").item(0);
                result.add(List.of(point(line, "1"), point(line, "2")));
            }
        }
        return result;
    }

    private static Point2D.Double point(Element line, String suffix) {
        return new Point2D.Double(Double.parseDouble(line.getAttribute("x" + suffix)),
                Double.parseDouble(line.getAttribute("y" + suffix)));
    }

    private static void assertDirectedClear(List<Point2D.Double> path, DiagramScene scene, DiagramSceneEdge edge) {
        assertThat(path.getFirst().distance(edge.sourceX(), edge.sourceY())).isLessThan(.01);
        assertThat(path.getLast().distance(edge.targetX(), edge.targetY())).isLessThan(.01);
        for (var node : scene.nodes()) {
            var interior = new Rectangle2D.Double(node.x() + .25, node.y() + .25, node.width() - .5, node.height() - .5);
            for (int index = 1; index < path.size(); index++)
                assertThat(interior.intersectsLine(path.get(index - 1).x, path.get(index - 1).y,
                        path.get(index).x, path.get(index).y)).as("connector crosses %s", node.id()).isFalse();
        }
        assertThat(path).allMatch(point -> point.x >= 0 && point.x <= scene.width() && point.y >= 0 && point.y <= scene.height());
    }
}
