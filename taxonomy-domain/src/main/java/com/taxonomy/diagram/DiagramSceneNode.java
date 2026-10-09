package com.taxonomy.diagram;

/** A diagram node with deterministic render coordinates. */
public record DiagramSceneNode(
        String id,
        String label,
        String type,
        double relevance,
        boolean anchor,
        int layer,
        int depth,
        boolean selectedForImpact,
        String parentId,
        boolean container,
        double x,
        double y,
        double width,
        double height) {
}
