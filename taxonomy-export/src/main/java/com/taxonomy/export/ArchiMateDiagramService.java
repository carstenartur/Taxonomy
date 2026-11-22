package com.taxonomy.export;

import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import com.taxonomy.archimate.exchange.ArchiMateIds;
import com.taxonomy.archimate.exchange.ArchiMateSchema;

import com.taxonomy.archimate.ArchiMateElement;
import com.taxonomy.archimate.ArchiMateProperty;
import com.taxonomy.archimate.ArchiMateLoss;
import com.taxonomy.archimate.ArchiMateModel;
import com.taxonomy.archimate.ArchiMateRelationship;
import com.taxonomy.archimate.ArchiMateView;
import com.taxonomy.archimate.ArchiMateViewConnection;
import com.taxonomy.archimate.ArchiMateViewNode;
import com.taxonomy.diagram.DiagramEdge;
import com.taxonomy.diagram.DiagramModel;
import com.taxonomy.diagram.DiagramNode;
import com.taxonomy.diagram.DiagramScene;
import com.taxonomy.diagram.DiagramSceneEdge;
import com.taxonomy.diagram.DiagramSceneNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Converts a neutral {@link DiagramModel} into an {@link ArchiMateModel} by mapping
 * taxonomy types to ArchiMate element types, relationship types to ArchiMate relationship
 * types, and computing pixel-based layout positions for the diagram view.
 */
public class ArchiMateDiagramService {

    private static final int MIN_NODE_WIDTH = 220;
    private static final int MAX_NODE_WIDTH = 340;
    private static final int MIN_NODE_HEIGHT = 52;
    private static final int LABEL_HORIZONTAL_INSET = 54;
    private static final int LABEL_VERTICAL_INSET = 22;
    private static final int LABEL_CHARACTER_WIDTH = 9;
    private static final int LABEL_LINE_HEIGHT = 17;
    private static final int LAYER_GAP = 144;
    private static final int ROW_GAP = 48;
    private static final int MARGIN = 40;
    private static final int MAX_ROUTING_NODE_EDGE_PAIRS = 20_000;
    private static final Pattern LABEL_WORD = Pattern.compile("\\S+");

    /**
     * Converts a {@link DiagramModel} to an {@link ArchiMateModel}.
     */
    public ArchiMateModel convert(DiagramModel model) {
        if (model.nodes().size() > ArchiMateSchema.MAX_ELEMENTS || model.edges().size() > ArchiMateSchema.MAX_RELATIONSHIPS) {
            throw new IllegalArgumentException("Model exceeds bounded exchange profile");
        }
        Set<String> allIds = model.nodes().stream().map(DiagramNode::id).collect(Collectors.toSet());
        for (DiagramEdge edge : model.edges()) {
            if (!allIds.contains(edge.sourceId()) || !allIds.contains(edge.targetId())) {
                throw new IllegalArgumentException("Unresolved relationship endpoint: " + edge.id());
            }
        }
        for (DiagramNode node : model.nodes()) {
            if (node.parentId() != null && (!allIds.contains(node.parentId()) || node.id().equals(node.parentId()))) {
                throw new IllegalArgumentException("Invalid node parent: " + node.id());
            }
        }
        // Collect non-container node IDs — container-only nodes are visual grouping
        // constructs and must not appear as ArchiMate elements or relationship endpoints.
        Set<String> elementIds = model.nodes().stream()
                .filter(n -> !n.container())
                .map(DiagramNode::id)
                .collect(Collectors.toSet());

        List<ArchiMateLoss> losses = new ArrayList<>();
        List<ArchiMateElement> elements = buildElements(model.nodes(), losses);
        List<ArchiMateRelationship> relationships = buildRelationships(model.edges(), elementIds, losses);
        Map<String, List<String>> organizations = buildOrganizations(model.nodes());
        ArchiMateView view = buildView(model, elementIds);
        Map<String, ArchiMateProperty> properties = new LinkedHashMap<>();
        properties.put("taxonomy.mappingProfile", ArchiMateProperty.text(ArchiMateExchangeProfile.VERSION));
        properties.put("taxonomy.exporterVersion", ArchiMateProperty.text("archimate-writer-v2"));
        properties.put("taxonomy.idProfile", ArchiMateProperty.text("length-prefixed-utf8-hex-v1"));
        properties.put("taxonomy.scoreSemantics", ArchiMateProperty.text("relevance in [0,1]; anchor and impact are separate selection flags"));
        if (model.layout() != null) {
            properties.put("taxonomy.layoutDirection", ArchiMateProperty.text(model.layout().direction()));
            properties.put("taxonomy.groupByLayer", ArchiMateProperty.flag(model.layout().groupByLayer()));
        }
        List<ArchiMateView> views = new ArrayList<>();
        views.add(view);
        Set<String> impact = model.nodes().stream().filter(n -> n.anchor() || n.selectedForImpact())
                .map(DiagramNode::id).filter(elementIds::contains).collect(Collectors.toSet());
        if (!impact.isEmpty() && impact.size() < elementIds.size()) {
            views.add(new ArchiMateView("impact", "Anchor and impact selection",
                    view.nodes().stream().filter(n -> impact.contains(n.elementId())).toList(),
                    view.connections().stream().filter(c -> impact.contains(c.sourceNodeId())
                            && impact.contains(c.targetNodeId())).toList()));
        }
        // Unbound model identity describes typed membership and relationship topology,
        // independent of display text, scores, ordering and generated layout.
        String identity;
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            elements.stream().sorted(Comparator.comparing(ArchiMateElement::id)).forEach(element -> digest.update(
                    ArchiMateIds.id("element", element.id(), element.properties().get("taxonomy.type").value())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            relationships.stream().sorted(Comparator.comparing(ArchiMateRelationship::id)).forEach(relation -> digest.update(
                    ArchiMateIds.id("relationship", relation.id(), relation.sourceId(), relation.targetId(),
                            relation.properties().get("taxonomy.type").value()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            identity = "graph-" + java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        return new ArchiMateModel(identity,
                model.title() != null ? model.title() : "Architecture View", elements,
                relationships, organizations, views, properties, losses);
    }

    private List<ArchiMateElement> buildElements(List<DiagramNode> nodes, List<ArchiMateLoss> losses) {
        List<ArchiMateElement> elements = new ArrayList<>();
        Set<String> identities = new java.util.HashSet<>();
        for (DiagramNode node : nodes) {
            ArchiMateIds.requireIdentity(node.id());
            if (!identities.add(node.id())) throw new IllegalArgumentException("Duplicate node identity: " + node.id());
            if (node.container()) {
                losses.add(new ArchiMateLoss("element", node.id(), "container", "OMITTED",
                        "Visual-only container is not an architecture element; grouping geometry is omitted."));
                continue;
            }
            var mapping = ArchiMateExchangeProfile.element(node.type());
            Map<String, ArchiMateProperty> properties = new LinkedHashMap<>();
            properties.put("taxonomy.type", ArchiMateProperty.text(node.type()));
            if (node.catalogueUuid() != null) properties.put("taxonomy.catalogueUuid", ArchiMateProperty.text(node.catalogueUuid()));
            properties.put("taxonomy.relevance", score(node.relevance()));
            properties.put("taxonomy.anchor", ArchiMateProperty.flag(node.anchor()));
            properties.put("taxonomy.selectedForImpact", ArchiMateProperty.flag(node.selectedForImpact()));
            properties.put("taxonomy.layer", ArchiMateProperty.number(node.layer()));
            properties.put("taxonomy.depth", ArchiMateProperty.number(node.depth()));
            if (node.parentId() != null) properties.put("taxonomy.parentId", ArchiMateProperty.text(node.parentId()));
            losses.add(new ArchiMateLoss("element", node.id(), "type", mapping.kind(), mapping.rationale()));
            elements.add(new ArchiMateElement(node.id(), node.label(), mapping.targetType(),
                    null, properties));
        }
        return elements;
    }

    private List<ArchiMateRelationship> buildRelationships(List<DiagramEdge> edges,
                                                           Set<String> elementIds, List<ArchiMateLoss> losses) {
        List<ArchiMateRelationship> relationships = new ArrayList<>();
        Set<String> identities = new java.util.HashSet<>();
        for (DiagramEdge edge : edges) {
            ArchiMateIds.requireIdentity(edge.id());
            if (!identities.add(edge.id())) throw new IllegalArgumentException("Duplicate relationship identity: " + edge.id());
            if (!elementIds.contains(edge.sourceId()) || !elementIds.contains(edge.targetId())) {
                losses.add(new ArchiMateLoss("relationship", edge.id(), "endpoints", "OMITTED",
                        "Relationship touches an omitted visual container."));
                continue;
            }
            var mapping = ArchiMateExchangeProfile.relationship(edge.relationType());
            Map<String, ArchiMateProperty> properties = new LinkedHashMap<>();
            properties.put("taxonomy.type", ArchiMateProperty.text(edge.relationType()));
            properties.put("taxonomy.relevance", score(edge.relevance()));
            if (edge.relationCategory() != null) properties.put("taxonomy.relationCategory", ArchiMateProperty.text(edge.relationCategory()));
            properties.putAll(com.taxonomy.archimate.exchange.ArchiMateRelationProvenance.write(edge.metadata()));
            losses.add(new ArchiMateLoss("relationship", edge.id(), "type", mapping.kind(), mapping.rationale()));
            relationships.add(new ArchiMateRelationship(edge.id(), edge.sourceId(), edge.targetId(),
                    mapping.targetType(), mapping.accessType(), edge.relationType(), properties));
        }
        return relationships;
    }

    private static ArchiMateProperty score(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("Relevance must be in [0,1]");
        return ArchiMateProperty.number(value);
    }

    private Map<String, List<String>> buildOrganizations(List<DiagramNode> nodes) {
        Map<String, List<String>> organizations = new LinkedHashMap<>();
        for (DiagramNode node : nodes) {
            if (node.container()) continue; // grouping-only nodes are not architecture elements
            String type = node.type() != null ? node.type() : "Unknown";
            organizations.computeIfAbsent(type, k -> new ArrayList<>()).add(node.id());
        }
        return organizations;
    }

    private ArchiMateView buildView(DiagramModel model, Set<String> elementIds) {
        Map<Integer, List<DiagramNode>> layerGroups = new TreeMap<>();
        for (DiagramNode node : model.nodes()) {
            if (node.container()) continue;
            layerGroups.computeIfAbsent(node.layer(), k -> new ArrayList<>()).add(node);
        }

        List<ArchiMateViewNode> viewNodes = new ArrayList<>();
        Map<String, Integer> nodeOrder = new HashMap<>();
        int x = MARGIN;
        for (List<DiagramNode> layerNodes : layerGroups.values()) {
            int y = MARGIN;
            int layerWidth = 0;
            for (DiagramNode node : layerNodes) {
                String sizingLabel = Objects.requireNonNullElse(node.label(), node.id());
                int width = nodeWidth(sizingLabel);
                int height = nodeHeight(sizingLabel, width);
                int[] color = toColor(node.type());
                int lineWidth = node.anchor() ? 3 : 1;
                nodeOrder.put(node.id(), viewNodes.size());
                viewNodes.add(new ArchiMateViewNode(
                        node.id(),
                        node.id(),
                        x, y,
                        width, height,
                        node.label(),
                        color[0], color[1], color[2],
                        lineWidth));
                y += height + ROW_GAP;
                layerWidth = Math.max(layerWidth, width);
            }
            x += layerWidth + LAYER_GAP;
        }

        List<ArchiMateViewConnection> viewConnections = new ArrayList<>();
        for (DiagramEdge edge : model.edges()) {
            if (elementIds.contains(edge.sourceId()) && elementIds.contains(edge.targetId())) {
                viewConnections.add(new ArchiMateViewConnection(
                        edge.id(),
                        edge.id(),
                        edge.sourceId(),
                        edge.targetId()));
            }
        }
        // Archi serializes outgoing connections in view-node order. Match that
        // order while retaining the sequence of connections from each source.
        viewConnections.sort(Comparator.comparingInt(connection -> nodeOrder.get(connection.sourceNodeId())));

        String viewName = model.title() != null ? model.title() : "Architecture View";
        return new ArchiMateView("layered", viewName, viewNodes, routeConnections(viewNodes, viewConnections));
    }

    private static List<ArchiMateViewConnection> routeConnections(List<ArchiMateViewNode> viewNodes,
                                                                  List<ArchiMateViewConnection> connections) {
        // Testing candidate paths against every card is useful on ordinary views.
        // Keep conversion bounded for the largest permitted exchange graphs.
        if (connections.isEmpty() || (long) viewNodes.size() * connections.size() > MAX_ROUTING_NODE_EDGE_PAIRS) {
            return connections;
        }
        var cards = viewNodes.stream().map(node -> new DiagramSceneNode(node.id(), node.label(), null, 0,
                false, 0, 0, false, null, false, node.x(), node.y(), node.w(), node.h())).toList();
        var byId = cards.stream().collect(Collectors.toMap(DiagramSceneNode::id, node -> node));
        var edges = connections.stream().map(connection -> {
            var source = byId.get(connection.sourceNodeId());
            var target = byId.get(connection.targetNodeId());
            var sourceX = source.x() <= target.x() ? source.x() + source.width() : source.x();
            var targetX = source.x() >= target.x() ? target.x() + target.width() : target.x();
            return new DiagramSceneEdge(connection.id(), source.id(), target.id(), null, 0, null,
                    sourceX, source.y() + source.height() / 2, targetX, target.y() + target.height() / 2);
        }).toList();
        double width = cards.stream().mapToDouble(card -> card.x() + card.width()).max().orElseThrow() + MARGIN;
        double height = cards.stream().mapToDouble(card -> card.y() + card.height()).max().orElseThrow() + MARGIN;
        var routing = new DiagramEdgeRouting(new DiagramScene(null, width, height, "LR", cards, edges));
        var result = new ArrayList<ArchiMateViewConnection>(connections.size());
        for (int i = 0; i < connections.size(); i++) {
            var connection = connections.get(i);
            var detour = routing.detourForLine(edges.get(i));
            if (detour == null) {
                result.add(connection);
                continue;
            }
            var points = detour.points();
            var bends = points.subList(1, points.size() - 1).stream()
                    .map(point -> new ArchiMateViewConnection.Bendpoint(
                            Math.toIntExact(Math.round(point.x())), Math.toIntExact(Math.round(point.y())))).toList();
            result.add(new ArchiMateViewConnection(connection.id(), connection.relationshipId(), connection.sourceNodeId(),
                    connection.targetNodeId(), bends));
        }
        return result;
    }

    private static int nodeWidth(String label) {
        int maximumCharacters = (MAX_NODE_WIDTH - LABEL_HORIZONTAL_INSET) / LABEL_CHARACTER_WIDTH + 1;
        return Math.clamp(LABEL_HORIZONTAL_INSET + Math.min(label.length(), maximumCharacters) * LABEL_CHARACTER_WIDTH,
                MIN_NODE_WIDTH, MAX_NODE_WIDTH);
    }

    private static int nodeHeight(String label, int width) {
        // Allow for the native ArchiMate symbol and conservatively budget glyph widths
        // so geometry is the same on exporter hosts with different system fonts.
        int columns = (width - LABEL_HORIZONTAL_INSET) / LABEL_CHARACTER_WIDTH;
        int lines = 1;
        int usedColumns = 0;
        var words = LABEL_WORD.matcher(label);
        while (words.find()) {
            int wordLength = words.end() - words.start();
            if (usedColumns > 0 && usedColumns + 1 + wordLength > columns) {
                lines++;
                usedColumns = 0;
            }
            if (usedColumns == 0) {
                lines += (wordLength - 1) / columns;
                usedColumns = (wordLength - 1) % columns + 1;
            } else {
                usedColumns += 1 + wordLength;
            }
        }
        return Math.max(MIN_NODE_HEIGHT, LABEL_VERTICAL_INSET + lines * LABEL_LINE_HEIGHT);
    }

    // ── Type mappings ─────────────────────────────────────────────────────────

    public static String toArchiMateType(String taxonomyType) {
        return ArchiMateExchangeProfile.element(taxonomyType).targetType();
    }

    public static String toArchiMateRelType(String relationType) {
        return ArchiMateExchangeProfile.relationship(relationType).targetType();
    }

    public static String toAccessType(String relationType) {
        return ArchiMateExchangeProfile.relationship(relationType).accessType();
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
            case "System"                 -> new int[]{204, 204, 255};
            case "Component"              -> new int[]{224, 204, 255};
            default                       -> new int[]{224, 224, 224};
        };
    }
}
