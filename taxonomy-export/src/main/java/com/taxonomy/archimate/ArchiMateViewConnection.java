package com.taxonomy.archimate;

import java.util.List;

/**
 * A connection between two view nodes in an ArchiMate diagram view.
 */
public record ArchiMateViewConnection(
        String id,
        String relationshipId,
        String sourceNodeId,
        String targetNodeId,
        List<Bendpoint> bendpoints) {

    public ArchiMateViewConnection {
        bendpoints = List.copyOf(bendpoints);
    }

    public ArchiMateViewConnection(String id, String relationshipId, String sourceNodeId, String targetNodeId) {
        this(id, relationshipId, sourceNodeId, targetNodeId, List.of());
    }

    /** Reverse the traversal of the existing route, preserving every point and both occurrence IDs. */
    public ArchiMateViewConnection reversed() {
        return new ArchiMateViewConnection(id, relationshipId, targetNodeId, sourceNodeId, bendpoints.reversed());
    }

    public record Bendpoint(int x, int y) { }
}
