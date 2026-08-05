package com.taxonomy.export;

import com.taxonomy.diagram.DiagramEdge;
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
    static final double ROW_GAP = 34.0;
    static final double MIN_WIDTH = 760.0;
    static final double MIN_HEIGHT = 420.0;

    public DiagramScene layout(DiagramModel model) {
        if (model == null || model.nodes() == null || model.nodes().isEmpty()) {
            return new DiagramScene(
                    model != null ? model.title() : "Architecture",
                    MIN_WIDTH,
                    MIN_HEIGHT,
                    direction(model),
                    List.of(),
                    List.of());
        }

        List<DiagramNode> ordered = model.nodes().stream()
                .sorted(Comparator
                        .comparingInt(DiagramNode::layer)
                        .thenComparing(node -> safe(node.type()))
                        .thenComparing(node -> safe(node.id())))
                .toList();

        Map<Integer, List<DiagramNode>> byLayer = new LinkedHashMap<>();
        for (DiagramNode node : ordered) {
            byLayer.computeIfAbsent(node.layer(), ignored -> new ArrayList<>()).add(node);
        }

        List<DiagramSceneNode> sceneNodes = new ArrayList<>(ordered.size());
        Map<String, DiagramSceneNode> byId = new LinkedHashMap<>();
        int column = 0;
        int maximumRows = 0;
        for (List<DiagramNode> layerNodes : byLayer.values()) {
            maximumRows = Math.max(maximumRows, layerNodes.size());
            for (int row = 0; row < layerNodes.size(); row++) {
                DiagramNode node = layerNodes.get(row);
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
                        MARGIN + column * (NODE_WIDTH + COLUMN_GAP),
                        MARGIN + row * (NODE_HEIGHT + ROW_GAP),
                        NODE_WIDTH,
                        NODE_HEIGHT);
                sceneNodes.add(sceneNode);
                byId.put(sceneNode.id(), sceneNode);
            }
            column++;
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
                        target.y() + target.height() / 2.0));
            }
        }

        double width = Math.max(
                MIN_WIDTH,
                2 * MARGIN + byLayer.size() * NODE_WIDTH
                        + Math.max(0, byLayer.size() - 1) * COLUMN_GAP);
        double height = Math.max(
                MIN_HEIGHT,
                2 * MARGIN + maximumRows * NODE_HEIGHT
                        + Math.max(0, maximumRows - 1) * ROW_GAP);

        return new DiagramScene(
                model.title(),
                width,
                height,
                direction(model),
                sceneNodes,
                sceneEdges);
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
