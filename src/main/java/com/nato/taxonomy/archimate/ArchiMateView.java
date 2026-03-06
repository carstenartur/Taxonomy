package com.nato.taxonomy.archimate;

import java.util.List;

/**
 * A diagram view in an ArchiMate model, containing positioned nodes and connections.
 */
public record ArchiMateView(
        String id,
        String name,
        List<ArchiMateViewNode> nodes,
        List<ArchiMateViewConnection> connections) {}
