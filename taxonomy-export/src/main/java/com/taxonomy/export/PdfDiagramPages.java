package com.taxonomy.export;

import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;
import java.util.*;

/** Readable PDF panels, preserving relative coordinates and complete canonical inventory. */
final class PdfDiagramPages {
    private static final double WIDTH = 14_336;
    private static final double HEIGHT = 14_282;
    private static final double INSET = 52;
    private static final int MAX_PANELS = 256;

    record Panel(DiagramScene scene, int crossPageRelationships) { }
    record Plan(List<Panel> panels, Map<String, Integer> nodePages) { }
    private record Cell(long column, long row) implements Comparable<Cell> {
        @Override public int compareTo(Cell other) {
            int order = Long.compare(column, other.column);
            return order == 0 ? Long.compare(row, other.row) : order;
        }
    }
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
        // Preserve established one-page coordinates and the existing readable poster range.
        if (Math.min(WIDTH / Math.max(1, scene.width()), HEIGHT / Math.max(1, scene.height())) >= .8) {
            Map<String, Integer> pages = new LinkedHashMap<>();
            scene.nodes().forEach(node -> pages.put(node.id(), 1));
            return new Plan(List.of(new Panel(scene, 0)), Map.copyOf(pages));
        }
        double stepX = WIDTH - largestWidth - 2 * INSET;
        double stepY = HEIGHT - largestHeight - 2 * INSET;
        if (stepX <= 0 || stepY <= 0) throw invalid("single node exceeds readable PDF panel dimensions");
        Map<Cell, List<DiagramSceneNode>> cells = new TreeMap<>();
        for (var node : scene.nodes()) {
            Cell cell = new Cell((long) Math.floor(node.x() / stepX), (long) Math.floor(node.y() / stepY));
            cells.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(node);
            if (cells.size() > MAX_PANELS) throw invalid("more than " + MAX_PANELS + " occupied PDF panels");
        }
        Map<String, Integer> nodePages = new LinkedHashMap<>();
        int number = 0;
        for (var nodes : cells.values()) {
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
        for (var nodes : cells.values()) {
            double x = nodes.stream().mapToDouble(DiagramSceneNode::x).min().orElseThrow() - INSET;
            double y = nodes.stream().mapToDouble(DiagramSceneNode::y).min().orElseThrow() - INSET;
            double width = nodes.stream().mapToDouble(n -> n.x() + n.width()).max().orElseThrow() - x + INSET;
            double height = nodes.stream().mapToDouble(n -> n.y() + n.height()).max().orElseThrow() - y + INSET;
            List<DiagramSceneNode> translated = nodes.stream().map(n -> new DiagramSceneNode(
                    n.id(), n.label(), n.type(), n.relevance(), n.anchor(), n.layer(), n.depth(),
                    n.selectedForImpact(), n.parentId(), n.container(), n.x() - x, n.y() - y, n.width(), n.height())).toList();
            List<DiagramSceneEdge> translatedEdges = edges.get(panels.size()).stream().map(e -> new DiagramSceneEdge(
                    e.id(), e.sourceId(), e.targetId(), e.relationType(), e.relevance(), e.relationCategory(),
                    e.sourceX() - x, e.sourceY() - y, e.targetX() - x, e.targetY() - y)).toList();
            var panel = new DiagramScene(scene.title(), Math.max(760, width), Math.max(420, height),
                    scene.direction(), translated, translatedEdges);
            panels.add(new Panel(panel, crossing[panels.size()]));
        }
        return new Plan(List.copyOf(panels), Map.copyOf(nodePages));
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1e12) throw invalid("invalid scene coordinate or dimension");
    }
    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("Cannot create readable PDF: " + detail);
    }
}
