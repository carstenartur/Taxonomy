package com.taxonomy.reporting.api.document;

import com.taxonomy.diagram.*;
import java.util.*;
import java.util.stream.Collectors;

/** Integrity rules for the graph and scene carried by a frozen report. No layout or live reads. */
public final class ArchitectureEvidence {
    private ArchitectureEvidence() {}

    public static void validateEvidence(DiagramModel model, DiagramScene scene) {
        if (model == null
                || scene == null
                || model.nodes() == null
                || model.nodes().isEmpty()
                || model.edges() == null)
            throw new IllegalArgumentException("Missing frozen architecture graph");
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
