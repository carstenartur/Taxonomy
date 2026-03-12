package com.taxonomy.diagram;

import java.util.List;

public record DiagramModel(
    String title,
    List<DiagramNode> nodes,
    List<DiagramEdge> edges,
    DiagramLayout layout
) {}
