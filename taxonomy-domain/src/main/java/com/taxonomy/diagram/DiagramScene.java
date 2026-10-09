package com.taxonomy.diagram;

import java.util.List;

/** Render-ready projection shared by browser, SVG and PDF adapters. */
public record DiagramScene(
        String title,
        double width,
        double height,
        String direction,
        List<DiagramSceneNode> nodes,
        List<DiagramSceneEdge> edges) {

    public DiagramScene {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}
