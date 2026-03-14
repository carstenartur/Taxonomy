package com.taxonomy.export;

import com.taxonomy.archimate.ArchiMateElement;
import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.archimate.ArchiMateRelationship;
import com.taxonomy.archimate.ArchiMateView;
import com.taxonomy.archimate.ArchiMateViewConnection;
import com.taxonomy.archimate.ArchiMateViewNode;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
            case "Capabilities"          -> "Capability";
            case "Business Processes"    -> "BusinessProcess";
            case "Business Roles"        -> "BusinessRole";
            case "Services"              -> "ApplicationService";
            case "COI Services"          -> "BusinessService";
            case "Communications Services" -> "CommunicationNetwork";
            case "Core Services"         -> "TechnologyService";
            case "Applications"          -> "ApplicationComponent";
            case "User Applications"     -> "ApplicationComponent";
            case "Information Products"  -> "DataObject";
            case "Systems"               -> "ApplicationComponent";
            case "Components"            -> "ApplicationComponent";
            default                      -> "BusinessObject";
        };
    }

    public static String toArchiMateRelType(String relationType) {
        if (relationType == null) return "Association";
        return switch (relationType) {
            case "SUPPORTS", "ENABLES", "USES", "DEPENDS_ON" -> "Serving";
            case "PRODUCES"                                   -> "Access";
            case "CONSUMES"                                   -> "Access";
            case "REALIZES", "IMPLEMENTS", "FULFILLS"         -> "Realization";
            case "ASSIGNED_TO"                                -> "Assignment";
            case "COMMUNICATES_WITH"                          -> "Flow";
            case "PART_OF"                                    -> "Composition";
            case "CONTAINS"                                   -> "Composition";
            case "RELATED_TO"                                 -> "Association";
            default                                           -> "Association";
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
            case "Capabilities"           -> new int[]{255, 255, 181};
            case "Business Processes"     -> new int[]{255, 255, 181};
            case "Business Roles"         -> new int[]{255, 224, 181};
            case "Services"               -> new int[]{181, 255, 255};
            case "COI Services"           -> new int[]{181, 255, 255};
            case "Core Services"          -> new int[]{181, 255, 255};
            case "Communications Services"-> new int[]{204, 224, 255};
            case "Applications"           -> new int[]{181, 255, 255};
            case "User Applications"      -> new int[]{181, 255, 255};
            case "Information Products"   -> new int[]{204, 255, 204};
            case "Systems"                -> new int[]{181, 204, 255};
            case "Components"             -> new int[]{204, 181, 255};
            default                       -> new int[]{224, 224, 224};
        };
    }
}
