package com.nato.taxonomy.service;

import com.nato.taxonomy.diagram.DiagramEdge;
import com.nato.taxonomy.diagram.DiagramModel;
import com.nato.taxonomy.diagram.DiagramNode;
import com.nato.taxonomy.visio.VisioConnect;
import com.nato.taxonomy.visio.VisioDocument;
import com.nato.taxonomy.visio.VisioPage;
import com.nato.taxonomy.visio.VisioShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a neutral {@link DiagramModel} into a {@link VisioDocument} by laying
 * out shapes on a single page using layer-based positioning.
 */
@Service
public class VisioDiagramService {

    private static final Logger log = LoggerFactory.getLogger(VisioDiagramService.class);

    private static final double SHAPE_WIDTH = 2.0;
    private static final double SHAPE_HEIGHT = 0.75;
    private static final double H_GAP = 3.0;
    private static final double V_GAP = 1.2;
    private static final double MARGIN_X = 1.5;
    private static final double MARGIN_Y = 1.5;

    /**
     * Converts a {@link DiagramModel} to a {@link VisioDocument} with one page.
     */
    public VisioDocument convert(DiagramModel model) {
        VisioDocument doc = new VisioDocument();
        VisioPage page = new VisioPage("0", model.title() != null ? model.title() : "Architecture View");
        doc.getPages().add(page);

        // Group nodes by layer for layout
        Map<Integer, List<DiagramNode>> layerGroups = new LinkedHashMap<>();
        for (DiagramNode node : model.nodes()) {
            layerGroups.computeIfAbsent(node.layer(), k -> new java.util.ArrayList<>()).add(node);
        }

        // Assign positions: layers left-to-right, nodes top-to-bottom within a layer
        Map<String, String> nodeIdToShapeId = new LinkedHashMap<>();
        int shapeIdx = 0;
        int layerIdx = 0;
        for (var entry : layerGroups.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey)).toList()) {
            List<DiagramNode> layerNodes = entry.getValue();
            for (int i = 0; i < layerNodes.size(); i++) {
                DiagramNode node = layerNodes.get(i);
                shapeIdx++;
                String shapeId = String.valueOf(shapeIdx);
                nodeIdToShapeId.put(node.id(), shapeId);

                double x = MARGIN_X + layerIdx * H_GAP;
                double y = MARGIN_Y + i * V_GAP;

                page.getShapes().add(new VisioShape(
                        shapeId,
                        node.label(),
                        x, y,
                        SHAPE_WIDTH, SHAPE_HEIGHT,
                        node.type(),
                        node.anchor()));
            }
            layerIdx++;
        }

        // Create connectors
        for (DiagramEdge edge : model.edges()) {
            String fromShape = nodeIdToShapeId.get(edge.sourceId());
            String toShape = nodeIdToShapeId.get(edge.targetId());
            if (fromShape != null && toShape != null) {
                page.getConnects().add(new VisioConnect(fromShape, toShape, edge.relationType()));
            }
        }

        log.info("VisioDiagram: {} shapes, {} connectors on page '{}'",
                page.getShapes().size(), page.getConnects().size(), page.getName());

        return doc;
    }
}
