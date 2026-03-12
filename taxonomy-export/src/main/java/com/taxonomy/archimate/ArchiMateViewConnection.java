package com.taxonomy.archimate;

/**
 * A connection between two view nodes in an ArchiMate diagram view.
 */
public record ArchiMateViewConnection(
        String id,
        String relationshipId,
        String sourceNodeId,
        String targetNodeId) {}
