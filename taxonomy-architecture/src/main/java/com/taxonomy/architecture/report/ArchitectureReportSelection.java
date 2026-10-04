package com.taxonomy.architecture.report;

import com.taxonomy.diagram.*;
import java.util.*;

/** Keeps selected nodes, crossing relation endpoints and their saved containers as explicit context. */
public final class ArchitectureReportSelection {
    public record Selection(DiagramModel graph, DiagramScene scene, Set<String> boundaryCodes, String sourceGraphSha256) {}
    private ArchitectureReportSelection() {}
    public static Selection select(DiagramModel graph, DiagramScene scene, Set<String> selectedCodes) {
        Set<String> included = new HashSet<>();
        graph.nodes().stream().filter(n -> selectedCodes.contains(n.id())).forEach(n -> included.add(n.id()));
        var edges = graph.edges().stream().filter(e -> selectedCodes.contains(e.sourceId()) || selectedCodes.contains(e.targetId())).toList();
        edges.forEach(e -> { included.add(e.sourceId()); included.add(e.targetId()); });
        boolean changed;
        do {
            changed = false;
            for (var node : graph.nodes()) if (included.contains(node.id()) && node.parentId() != null)
                changed |= included.add(node.parentId());
        } while (changed);
        var nodes = graph.nodes().stream().filter(n -> included.contains(n.id())).toList();
        var edgeIds = edges.stream().map(DiagramEdge::id).collect(java.util.stream.Collectors.toSet());
        var selected = new DiagramModel(graph.title(), nodes, edges, graph.layout());
        var selectedScene = new DiagramScene(scene.title(), scene.width(), scene.height(), scene.direction(),
                scene.nodes().stream().filter(n -> included.contains(n.id())).toList(),
                scene.edges().stream().filter(e -> edgeIds.contains(e.id())).toList());
        var boundary = new TreeSet<>(included); boundary.removeAll(selectedCodes);
        return new Selection(selected, selectedScene, Collections.unmodifiableSet(boundary), ArchitectureReportDocument.graphSha256(graph));
    }
}
