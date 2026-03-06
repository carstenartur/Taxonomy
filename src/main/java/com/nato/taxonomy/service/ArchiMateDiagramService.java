package com.nato.taxonomy.service;

import com.nato.taxonomy.archimate.ArchiMateElement;
import com.nato.taxonomy.archimate.ArchiMateModel;
import com.nato.taxonomy.archimate.ArchiMateRelationship;
import com.nato.taxonomy.archimate.ArchiMateView;
import com.nato.taxonomy.archimate.ArchiMateViewConnection;
import com.nato.taxonomy.archimate.ArchiMateViewNode;
import com.nato.taxonomy.diagram.DiagramEdge;
import com.nato.taxonomy.diagram.DiagramModel;
import com.nato.taxonomy.diagram.DiagramNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Converts a neutral {@link DiagramModel} into an {@link ArchiMateModel} by mapping
 * taxonomy types to ArchiMate element types, relationship types to ArchiMate relationship
 * types, and computing pixel-based layout positions for the diagram view.
 */
@Service
public class ArchiMateDiagramService {

    private static final Logger log = LoggerFactory.getLogger(ArchiMateDiagramService.class);

    // Layout constants (pixels)
    private static final int NODE_WIDTH  = 120;
    private static final int NODE_HEIGHT =  40;
    private static final int H_GAP       = 160;
    private static final int V_GAP       =  60;
    private static final int MARGIN_X    =  40;
    private static final int MARGIN_Y    =  40;

    /**
     * Converts a {@link DiagramModel} to an {@link ArchiMateModel}.
     */
    public ArchiMateModel convert(DiagramModel model) {
        List<ArchiMateElement> elements = buildElements(model.nodes());
        List<ArchiMateRelationship> relationships = buildRelationships(model.edges());
        Map<String, List<String>> organizations = buildOrganizations(model.nodes());
        ArchiMateView view = buildView(model);

        log.info("ArchiMateDiagram: {} elements, {} relationships, {} view nodes",
                elements.size(), relationships.size(), view.nodes().size());

        return new ArchiMateModel(
                model.title() != null ? model.title() : "Architecture View",
                elements,
                relationships,
                organizations,
                view);
    }

    private List<ArchiMateElement> buildElements(List<DiagramNode> nodes) {
        List<ArchiMateElement> elements = new ArrayList<>();
        for (DiagramNode node : nodes) {
            elements.add(new ArchiMateElement(
                    node.id(),
                    node.label(),
                    toArchiMateType(node.type()),
                    "Relevance: " + String.format("%.2f", node.relevance())));
        }
        return elements;
    }

    private List<ArchiMateRelationship> buildRelationships(List<DiagramEdge> edges) {
        List<ArchiMateRelationship> relationships = new ArrayList<>();
        for (DiagramEdge edge : edges) {
            relationships.add(new ArchiMateRelationship(
                    edge.id(),
                    edge.sourceId(),
                    edge.targetId(),
                    toArchiMateRelType(edge.relationType()),
                    toAccessType(edge.relationType()),
                    edge.relationType() != null ? edge.relationType() : ""));
        }
        return relationships;
    }

    private Map<String, List<String>> buildOrganizations(List<DiagramNode> nodes) {
        Map<String, List<String>> organizations = new LinkedHashMap<>();
        for (DiagramNode node : nodes) {
            String type = node.type() != null ? node.type() : "Unknown";
            organizations.computeIfAbsent(type, k -> new ArrayList<>()).add(node.id());
        }
        return organizations;
    }

    private ArchiMateView buildView(DiagramModel model) {
        // Group nodes by layer for left-to-right layout
        Map<Integer, List<DiagramNode>> layerGroups = new LinkedHashMap<>();
        for (DiagramNode node : model.nodes()) {
            layerGroups.computeIfAbsent(node.layer(), k -> new ArrayList<>()).add(node);
        }

        List<ArchiMateViewNode> viewNodes = new ArrayList<>();
        int layerIdx = 0;
        for (var entry : layerGroups.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey)).toList()) {
            List<DiagramNode> layerNodes = entry.getValue();
            for (int i = 0; i < layerNodes.size(); i++) {
                DiagramNode node = layerNodes.get(i);
                int x = MARGIN_X + layerIdx * H_GAP;
                int y = MARGIN_Y + i * V_GAP;
                int[] color = toColor(node.type());
                int lineWidth = node.anchor() ? 3 : 1;
                viewNodes.add(new ArchiMateViewNode(
                        node.id(),
                        node.id(),
                        x, y,
                        NODE_WIDTH, NODE_HEIGHT,
                        node.label(),
                        color[0], color[1], color[2],
                        lineWidth));
            }
            layerIdx++;
        }

        Set<String> nodeIdSet = model.nodes().stream()
                .map(DiagramNode::id).collect(Collectors.toSet());
        List<ArchiMateViewConnection> viewConnections = new ArrayList<>();
        for (DiagramEdge edge : model.edges()) {
            if (nodeIdSet.contains(edge.sourceId()) && nodeIdSet.contains(edge.targetId())) {
                viewConnections.add(new ArchiMateViewConnection(
                        edge.id(),
                        edge.id(),
                        edge.sourceId(),
                        edge.targetId()));
            }
        }

        String viewName = model.title() != null ? model.title() : "Architecture View";
        return new ArchiMateView("view-1", viewName, viewNodes, viewConnections);
    }

    // ── Type mappings ─────────────────────────────────────────────────────────

    public static String toArchiMateType(String taxonomyType) {
        if (taxonomyType == null) return "BusinessObject";
        return switch (taxonomyType) {
            case "Capabilities"        -> "Capability";
            case "Business Processes"  -> "BusinessProcess";
            case "Services"            -> "ApplicationService";
            case "Applications"        -> "ApplicationComponent";
            case "Information Products"-> "DataObject";
            default                    -> "BusinessObject";
        };
    }

    public static String toArchiMateRelType(String relationType) {
        if (relationType == null) return "Association";
        return switch (relationType) {
            case "SUPPORTS", "ENABLES" -> "Serving";
            case "PRODUCES"            -> "Access";
            case "CONSUMES"            -> "Access";
            case "IMPLEMENTS"          -> "Realization";
            case "PART_OF"             -> "Composition";
            default                    -> "Association";
        };
    }

    public static String toAccessType(String relationType) {
        if ("PRODUCES".equals(relationType)) return "Write";
        if ("CONSUMES".equals(relationType)) return "Read";
        return null;
    }

    public static int[] toColor(String taxonomyType) {
        if (taxonomyType == null) return new int[]{224, 224, 224};
        return switch (taxonomyType) {
            case "Capabilities"         -> new int[]{255, 255, 181};
            case "Business Processes"   -> new int[]{255, 255, 181};
            case "Services"             -> new int[]{181, 255, 255};
            case "Applications"         -> new int[]{181, 255, 255};
            case "Information Products" -> new int[]{204, 255, 204};
            default                     -> new int[]{224, 224, 224};
        };
    }
}
