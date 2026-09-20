package com.taxonomy.architecture.report;

import com.taxonomy.diagram.*;
import com.taxonomy.export.LayeredDiagramLayoutService;

import java.util.*;
import java.util.stream.Collectors;

/** Deterministic, complete graph partitions; all limits are checked before raster allocation. */
public final class ArchitectureFigurePlanner {
    public static final int MAX_NODES = 500, MAX_EDGES = 1000, MAX_PANELS = 250;
    public static final double MAX_DETAIL_WIDTH = 1010, MAX_DETAIL_HEIGHT = 650;

    public record Panel(
            String id,
            DiagramModel diagram,
            DiagramScene scene,
            List<String> nodeIds,
            List<String> edgeIds,
            boolean overview) {
        public Panel {
            nodeIds = List.copyOf(nodeIds);
            edgeIds = List.copyOf(edgeIds);
        }
    }

    public record Plan(Panel overview, List<Panel> details) {
        public Plan {
            details = List.copyOf(details);
        }
    }

    private final LayeredDiagramLayoutService layout = new LayeredDiagramLayoutService();

    public Plan plan(DiagramModel diagram, DiagramScene scene) {
        validate(diagram, scene);
        Map<String, DiagramNode> nodes =
                diagram.nodes().stream()
                        .sorted(Comparator.comparing(DiagramNode::id))
                        .collect(
                                Collectors.toMap(
                                        DiagramNode::id, n -> n, (a, b) -> a, LinkedHashMap::new));
        List<Panel> panels = new ArrayList<>();
        List<DiagramEdge> current = new ArrayList<>();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (var edge :
                diagram.edges().stream().sorted(Comparator.comparing(DiagramEdge::id)).toList()) {
            var proposed = new LinkedHashSet<>(ids);
            proposed.add(edge.sourceId());
            proposed.add(edge.targetId());
            var proposedEdges = new ArrayList<>(current);
            proposedEdges.add(edge);
            if (!current.isEmpty() && !fits(diagram, nodes, proposed, proposedEdges)) {
                panels.add(panel(diagram, nodes, ids, current, panels.size() + 1));
                current = new ArrayList<>();
                ids.clear();
            }
            ids.add(edge.sourceId());
            ids.add(edge.targetId());
            current.add(edge);
            if (!fits(diagram, nodes, ids, current))
                throw new IllegalArgumentException(
                        "Word detail panel cannot meet layout bounds: " + edge.id());
        }
        if (!current.isEmpty()) panels.add(panel(diagram, nodes, ids, current, panels.size() + 1));
        Set<String> covered =
                panels.stream().flatMap(p -> p.nodeIds().stream()).collect(Collectors.toSet());
        ids = new LinkedHashSet<>();
        for (String id : nodes.keySet()) {
            if (covered.contains(id)) continue;
            var proposed = new LinkedHashSet<>(ids);
            proposed.add(id);
            if (!ids.isEmpty() && !fits(diagram, nodes, proposed, List.of())) {
                panels.add(panel(diagram, nodes, ids, List.of(), panels.size() + 1));
                ids.clear();
            }
            ids.add(id);
        }
        if (!ids.isEmpty()) panels.add(panel(diagram, nodes, ids, List.of(), panels.size() + 1));
        if (panels.size() > MAX_PANELS)
            throw new IllegalArgumentException(
                    "Word document policy ceiling exceeded: detail panels");
        Set<String> actualNodes =
                panels.stream().flatMap(p -> p.nodeIds().stream()).collect(Collectors.toSet());
        Set<String> actualEdges =
                panels.stream().flatMap(p -> p.edgeIds().stream()).collect(Collectors.toSet());
        if (!actualNodes.equals(nodes.keySet())
                || !actualEdges.equals(
                        diagram.edges().stream().map(DiagramEdge::id).collect(Collectors.toSet())))
            throw new IllegalArgumentException("Incomplete architecture detail coverage");
        return new Plan(
                new Panel(
                        "architecture-overview",
                        diagram,
                        scene,
                        nodes.keySet().stream().toList(),
                        diagram.edges().stream().map(DiagramEdge::id).sorted().toList(),
                        true),
                panels);
    }

    private boolean fits(
            DiagramModel source,
            Map<String, DiagramNode> nodes,
            Set<String> ids,
            List<DiagramEdge> edges) {
        if (ids.size() > 12 || edges.size() > 18) return false;
        DiagramScene scene = layout.layout(subset(source, nodes, ids, edges));
        return scene.width() <= MAX_DETAIL_WIDTH && scene.height() <= MAX_DETAIL_HEIGHT;
    }

    private Panel panel(
            DiagramModel source,
            Map<String, DiagramNode> nodes,
            Set<String> ids,
            List<DiagramEdge> edges,
            int number) {
        DiagramModel model = subset(source, nodes, ids, edges);
        DiagramScene scene = layout.layout(model);
        if (ids.size() > 12
                || edges.size() > 18
                || scene.width() > MAX_DETAIL_WIDTH
                || scene.height() > MAX_DETAIL_HEIGHT)
            throw new IllegalArgumentException("Word detail panel exceeds actual scene bounds");
        return new Panel(
                "architecture-detail-" + number,
                model,
                scene,
                ids.stream().sorted().toList(),
                edges.stream().map(DiagramEdge::id).toList(),
                false);
    }

    private DiagramModel subset(
            DiagramModel source,
            Map<String, DiagramNode> nodes,
            Set<String> ids,
            List<DiagramEdge> edges) {
        return new DiagramModel(
                source.title(),
                ids.stream().sorted().map(nodes::get).toList(),
                List.copyOf(edges),
                source.layout());
    }

    public static void validate(DiagramModel model, DiagramScene scene) {
        if (model == null
                || scene == null
                || model.nodes() == null
                || model.nodes().isEmpty()
                || model.edges() == null)
            throw new IllegalArgumentException("Missing frozen architecture graph");
        if (model.nodes().size() > MAX_NODES || model.edges().size() > MAX_EDGES)
            throw new IllegalArgumentException(
                    "Word document policy ceiling exceeded: maximum 500 nodes and 1000 relations");
        var nodeIds = new HashSet<String>();
        var edgeIds = new HashSet<String>();
        for (var node : model.nodes())
            if (node.id() == null || node.id().isBlank() || !nodeIds.add(node.id()))
                throw new IllegalArgumentException("Invalid architecture node ID");
        for (var edge : model.edges()) {
            if (edge.id() == null || edge.id().isBlank() || !edgeIds.add(edge.id()))
                throw new IllegalArgumentException("Invalid architecture relation ID");
            if (!nodeIds.contains(edge.sourceId()) || !nodeIds.contains(edge.targetId()))
                throw new IllegalArgumentException(
                        "Missing architecture relation endpoint: " + edge.id());
        }
        if (scene.nodes().size() != nodeIds.size()
                || scene.edges().size() != edgeIds.size()
                || !nodeIds.equals(
                        scene.nodes().stream()
                                .map(DiagramSceneNode::id)
                                .collect(Collectors.toSet()))
                || !edgeIds.equals(
                        scene.edges().stream()
                                .map(DiagramSceneEdge::id)
                                .collect(Collectors.toSet())))
            throw new IllegalArgumentException("Incomplete frozen architecture scene");
        Map<String, DiagramNode> canonical =
                model.nodes().stream().collect(Collectors.toMap(DiagramNode::id, n -> n));
        for (var n : scene.nodes())
            if (!new DiagramNode(
                            n.id(),
                            n.label(),
                            n.type(),
                            n.relevance(),
                            n.anchor(),
                            n.layer(),
                            n.depth(),
                            n.selectedForImpact(),
                            n.parentId(),
                            n.container())
                    .equals(canonical.get(n.id())))
                throw new IllegalArgumentException(
                        "Contradictory architecture scene node " + n.id());
        Map<String, DiagramEdge> edges =
                model.edges().stream().collect(Collectors.toMap(DiagramEdge::id, e -> e));
        for (var e : scene.edges())
            if (!new DiagramEdge(
                            e.id(),
                            e.sourceId(),
                            e.targetId(),
                            e.relationType(),
                            e.relevance(),
                            e.relationCategory())
                    .equals(edges.get(e.id())))
                throw new IllegalArgumentException(
                        "Contradictory architecture scene relation " + e.id());
    }
}
