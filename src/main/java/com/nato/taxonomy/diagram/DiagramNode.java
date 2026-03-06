package com.nato.taxonomy.diagram;

public record DiagramNode(
    String id,
    String label,
    String type,
    double relevance,
    boolean anchor,
    int layer
) {}
