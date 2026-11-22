package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import java.util.*;
import java.util.function.ToDoubleFunction;

/** Readable PDF panels, preserving relative coordinates and complete canonical inventory. */
final class PdfDiagramPages {
    static final float PAGE_WIDTH = PDRectangle.A3.getHeight();
    static final float PAGE_HEIGHT = PDRectangle.A3.getWidth();
    static final float PAGE_MARGIN = 32;
    static final float HEADER_HEIGHT = 76;
    private static final double WIDTH = PAGE_WIDTH - 2 * PAGE_MARGIN;
    private static final double HEIGHT = PAGE_HEIGHT - 2 * PAGE_MARGIN - HEADER_HEIGHT;
    private static final double INSET = 52;
    private static final int MAX_PANELS = 256;

    record Panel(DiagramScene scene, int crossPageRelationships) { }
    record Plan(List<Panel> panels, Map<String, Integer> nodePages) { }
    private PdfDiagramPages() { }

    static Plan plan(DiagramScene scene) {
        requireFinite(scene.width()); requireFinite(scene.height());
        double largestWidth = 0, largestHeight = 0;
        Set<String> identities = new HashSet<>();
        for (var node : scene.nodes()) {
            if (node.id() == null || !identities.add(node.id())) throw invalid("duplicate or missing node identity");
            requireFinite(node.x()); requireFinite(node.y());
            requireFinite(node.width()); requireFinite(node.height());
            if (node.width() <= 0 || node.height() <= 0) throw invalid("nonpositive node dimensions");
            if (node.x() + node.width() > scene.width() || node.y() + node.height() > scene.height())
                throw invalid("node lies outside recorded scene bounds");
            largestWidth = Math.max(largestWidth, node.width());
            largestHeight = Math.max(largestHeight, node.height());
        }
        for (var edge : scene.edges()) {
            if (!identities.contains(edge.sourceId()) || !identities.contains(edge.targetId()))
                throw invalid("relationship has an unknown endpoint");
            requireFinite(edge.sourceX()); requireFinite(edge.sourceY());
            requireFinite(edge.targetX()); requireFinite(edge.targetY());
        }
        // Preserve the original geometry at readable scale whenever it fits on one sheet.
        if (Math.min(WIDTH / Math.max(1, scene.width()), HEIGHT / Math.max(1, scene.height())) >= .8) {
            Map<String, Integer> pages = new LinkedHashMap<>();
            scene.nodes().forEach(node -> pages.put(node.id(), 1));
            return new Plan(List.of(new Panel(scene, 0)), Map.copyOf(pages));
        }
        if (largestWidth > WIDTH - 2 * INSET || largestHeight > HEIGHT - 2 * INSET)
            throw invalid("single node exceeds readable PDF panel dimensions");
        // Start each sheet at the next occupied position and keep its full boxes together.
        // Fixed grid boundaries can strand an entire sheet's first or last column.
        var cells = new ArrayList<List<DiagramSceneNode>>();
        for (var column : slices(scene.nodes(), DiagramSceneNode::x, DiagramSceneNode::width, WIDTH)) {
            cells.addAll(slices(column, DiagramSceneNode::y, DiagramSceneNode::height, HEIGHT));
            if (cells.size() > MAX_PANELS) throw invalid("more than " + MAX_PANELS + " occupied PDF panels");
        }
        Map<String, Integer> nodePages = new LinkedHashMap<>();
        int number = 0;
        for (var nodes : cells) {
            number++;
            for (var node : nodes) nodePages.put(node.id(), number);
        }
        List<List<DiagramSceneEdge>> edges = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) edges.add(new ArrayList<>());
        int[] crossing = new int[cells.size()];
        for (var edge : scene.edges()) {
            int source = nodePages.get(edge.sourceId()) - 1, target = nodePages.get(edge.targetId()) - 1;
            if (source == target) edges.get(source).add(edge);
            else { crossing[source]++; crossing[target]++; }
        }
        List<Panel> panels = new ArrayList<>();
        for (var nodes : cells) {
            double x = nodes.stream().mapToDouble(DiagramSceneNode::x).min().orElseThrow() - INSET;
            double y = nodes.stream().mapToDouble(DiagramSceneNode::y).min().orElseThrow() - INSET;
            double width = nodes.stream().mapToDouble(n -> n.x() + n.width()).max().orElseThrow() - x + INSET;
            double height = nodes.stream().mapToDouble(n -> n.y() + n.height()).max().orElseThrow() - y + INSET;
            List<DiagramSceneNode> translated = nodes.stream().map(n -> new DiagramSceneNode(
                    n.id(), n.label(), n.type(), n.relevance(), n.anchor(), n.layer(), n.depth(),
                    n.selectedForImpact(), n.parentId(), n.container(), n.x() - x, n.y() - y, n.width(), n.height(),
                    n.responsibility(), n.missingMeasurements())).toList();
            List<DiagramSceneEdge> translatedEdges = edges.get(panels.size()).stream().map(e -> new DiagramSceneEdge(
                    e.id(), e.sourceId(), e.targetId(), e.relationType(), e.relevance(), e.relationCategory(),
                    e.sourceX() - x, e.sourceY() - y, e.targetX() - x, e.targetY() - y, e.metadata())).toList();
            var panel = new DiagramScene(scene.title(), Math.max(760, width), Math.max(420, height),
                    scene.direction(), translated, translatedEdges);
            panels.add(new Panel(panel, crossing[panels.size()]));
        }
        return new Plan(List.copyOf(panels), Map.copyOf(nodePages));
    }

    private static List<List<DiagramSceneNode>> slices(List<DiagramSceneNode> nodes,
            ToDoubleFunction<DiagramSceneNode> coordinate, ToDoubleFunction<DiagramSceneNode> dimension, double extent) {
        var result = new ArrayList<List<DiagramSceneNode>>();
        double boundary = -1;
        List<DiagramSceneNode> current = null;
        for (var node : nodes.stream().sorted(Comparator.comparingDouble(coordinate)).toList()) {
            double start = coordinate.applyAsDouble(node);
            if (start + dimension.applyAsDouble(node) > boundary) {
                boundary = start + extent - 2 * INSET;
                current = new ArrayList<>();
                result.add(current);
                if (result.size() > MAX_PANELS) throw invalid("more than " + MAX_PANELS + " occupied PDF panels");
            }
            current.add(node);
        }
        return result;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1e12) throw invalid("invalid scene coordinate or dimension");
    }
    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Cannot create readable PDF: " + detail);
    }
}
