package com.taxonomy.diagram;

public record DiagramEdge(
    String id,
    String sourceId,
    String targetId,
    String relationType,
    double relevance
) {}
