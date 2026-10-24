package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramLayout.Strategy;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic layered layout for architecture diagrams.
 *
 * <p>The renderer deliberately has no browser or Spring dependency. The same
 * coordinates are returned to the browser and used by SVG/PDF exporters.</p>
 */
public class LayeredDiagramLayoutService {

    static final double MARGIN = 52.0;
    static final double NODE_WIDTH = 238.0;
    static final double NODE_HEIGHT = 82.0;
    static final double COLUMN_GAP = 92.0;
    static final double SUBCOLUMN_GAP = 24.0;
    static final double ROW_GAP = 34.0;
    static final int MAX_ROWS_PER_COLUMN = 5;
    static final double MIN_WIDTH = 760.0;
    static final double MIN_HEIGHT = 420.0;

    public DiagramScene layout(DiagramModel model) { return layout(model, Strategy.STABLE); }

    public DiagramScene layout(DiagramModel model, Strategy strategy) { return layout(model, strategy, model); }

    /** Optional immutable reference supplies ordering, while current nodes and edges supply all semantics. */
    public DiagramScene layout(DiagramModel model, Strategy strategy, DiagramModel orderingReference) {
        if (model == null || model.nodes() == null || model.nodes().isEmpty()) {
            return new DiagramScene(
                    model != null ? model.title() : "Architecture",
                    MIN_WIDTH,
                    MIN_HEIGHT,
                    direction(model),
                    List.of(),
                    List.of());
        }

        Map<Integer, List<DiagramNode>> byLayer = layers(model);
        if (strategy == Strategy.REDUCE_CROSSINGS) {
            Map<Integer, List<DiagramNode>> reference = layers(orderingReference == null ? model : orderingReference);
            reduceCrossings(reference, orderingReference == null ? model : orderingReference);
            Map<String, Integer> positions = positions(reference);
            byLayer.values().forEach(nodes -> nodes.sort(Comparator.comparingInt(
                    node -> positions.getOrDefault(node.id(), Integer.MAX_VALUE))));
        }

        int maximumRows = byLayer.values().stream()
                .mapToInt(layer -> rowCount(layer.size()))
                .max()
                .orElse(1);
        double contentHeight = rowsHeight(maximumRows);

        List<DiagramSceneNode> sceneNodes = new ArrayList<>(model.nodes().size());
        Map<String, DiagramSceneNode> byId = new LinkedHashMap<>();
        double layerStartX = MARGIN;
        for (List<DiagramNode> layerNodes : byLayer.values()) {
            int rows = rowCount(layerNodes.size());
            double layerHeight = rowsHeight(rows);
            double firstRowY = MARGIN + (contentHeight - layerHeight) / 2.0;
            for (int index = 0; index < layerNodes.size(); index++) {
                DiagramNode node = layerNodes.get(index);
                int subcolumn = index / rows;
                int row = index % rows;
                DiagramSceneNode sceneNode = new DiagramSceneNode(
                        node.id(),
                        node.label(),
                        node.type(),
                        node.relevance(),
                        node.anchor(),
                        node.layer(),
                        node.depth(),
                        node.selectedForImpact(),
                        node.parentId(),
                        node.container(),
                        layerStartX + subcolumn * (NODE_WIDTH + SUBCOLUMN_GAP),
                        firstRowY + row * (NODE_HEIGHT + ROW_GAP),
                        NODE_WIDTH,
                        NODE_HEIGHT);
                sceneNodes.add(sceneNode);
                byId.put(sceneNode.id(), sceneNode);
            }
            layerStartX += layerWidth(layerNodes.size()) + COLUMN_GAP;
        }

        List<DiagramSceneEdge> sceneEdges = new ArrayList<>();
        if (model.edges() != null) {
            for (DiagramEdge edge : model.edges().stream()
                    .sorted(Comparator.comparing(item -> safe(item.id())))
                    .toList()) {
                DiagramSceneNode source = byId.get(edge.sourceId());
                DiagramSceneNode target = byId.get(edge.targetId());
                if (source == null || target == null) {
                    continue;
                }
                boolean leftToRight = source.x() <= target.x();
                double sourceX = leftToRight ? source.x() + source.width() : source.x();
                double targetX = leftToRight ? target.x() : target.x() + target.width();
                sceneEdges.add(new DiagramSceneEdge(
                        edge.id(),
                        edge.sourceId(),
                        edge.targetId(),
                        edge.relationType(),
                        edge.relevance(),
                        edge.relationCategory(),
                        sourceX,
                        source.y() + source.height() / 2.0,
                        targetX,
                        target.y() + target.height() / 2.0, edge.metadata()));
            }
        }

        double layerWidths = byLayer.values().stream()
                .mapToDouble(layer -> layerWidth(layer.size()))
                .sum();
        double width = Math.max(
                MIN_WIDTH,
                2 * MARGIN + layerWidths
                        + Math.max(0, byLayer.size() - 1) * COLUMN_GAP);
        double height = Math.max(MIN_HEIGHT, 2 * MARGIN + contentHeight);

        return new DiagramScene(
                model.title(),
                width,
                height,
                direction(model),
                sceneNodes,
                sceneEdges);
    }

    private static Map<Integer, List<DiagramNode>> layers(DiagramModel model) {
        Map<Integer, List<DiagramNode>> layers = new LinkedHashMap<>();
        if (model != null && model.nodes() != null) model.nodes().stream().sorted(Comparator
                .comparingInt(DiagramNode::layer).thenComparing(node -> safe(node.type()))
                .thenComparing(node -> safe(node.id())))
                .forEach(node -> layers.computeIfAbsent(node.layer(), ignored -> new ArrayList<>()).add(node));
        return layers;
    }

    private static Map<String, Integer> positions(Map<Integer, List<DiagramNode>> layers) {
        Map<String, Integer> positions = new java.util.HashMap<>();
        layers.values().forEach(nodes -> {
            for (int index = 0; index < nodes.size(); index++) positions.put(nodes.get(index).id(), index);
        });
        return positions;
    }

    /** Bounded barycenter sweeps retain semantic layers and stable tie order; crossing freedom is not promised. */
    private static void reduceCrossings(Map<Integer, List<DiagramNode>> layers, DiagramModel model) {
        Map<String, DiagramNode> nodes = new java.util.HashMap<>();
        layers.values().forEach(layer -> layer.forEach(node -> nodes.put(node.id(), node)));
        Map<String, List<DiagramNode>> neighbors = new java.util.HashMap<>();
        if (model.edges() != null) for (DiagramEdge edge : model.edges()) {
            DiagramNode source = nodes.get(edge.sourceId()), target = nodes.get(edge.targetId());
            if (source == null || target == null || source.layer() == target.layer()) continue;
            neighbors.computeIfAbsent(source.id(), ignored -> new ArrayList<>()).add(target);
            neighbors.computeIfAbsent(target.id(), ignored -> new ArrayList<>()).add(source);
        }
        List<List<DiagramNode>> orderedLayers = new ArrayList<>(layers.values());
        for (int sweep = 0; sweep < 4; sweep++) {
            boolean forward = sweep % 2 == 0;
            for (int index = 0; index < orderedLayers.size(); index++) {
                var layer = orderedLayers.get(forward ? index : orderedLayers.size() - 1 - index);
                Map<String, Integer> positions = positions(layers);
                Map<String, Double> scores = new java.util.HashMap<>();
                for (DiagramNode node : layer) scores.put(node.id(), neighbors.getOrDefault(node.id(), List.of()).stream()
                        .filter(other -> forward ? other.layer() < node.layer() : other.layer() > node.layer())
                        .mapToInt(other -> positions.get(other.id())).average().orElse(positions.get(node.id())));
                layer.sort(Comparator.comparingDouble(node -> scores.get(node.id())));
            }
        }
    }

    private static int subcolumnCount(int nodeCount) {
        int boundedCount = Math.max(1, nodeCount);
        return Math.max(1, (boundedCount + MAX_ROWS_PER_COLUMN - 1) / MAX_ROWS_PER_COLUMN);
    }

    private static int rowCount(int nodeCount) {
        int boundedCount = Math.max(1, nodeCount);
        int subcolumns = subcolumnCount(boundedCount);
        return Math.max(1, (boundedCount + subcolumns - 1) / subcolumns);
    }

    private static double layerWidth(int nodeCount) {
        int subcolumns = subcolumnCount(nodeCount);
        return subcolumns * NODE_WIDTH + Math.max(0, subcolumns - 1) * SUBCOLUMN_GAP;
    }

    private static double rowsHeight(int rows) {
        int boundedRows = Math.max(1, rows);
        return boundedRows * NODE_HEIGHT + Math.max(0, boundedRows - 1) * ROW_GAP;
    }

    private static String direction(DiagramModel model) {
        return model != null && model.layout() != null && model.layout().direction() != null
                ? model.layout().direction()
                : "LR";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
