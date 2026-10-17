package com.taxonomy.diagram;

/** A diagram edge with deterministic source and target coordinates. */
public record DiagramSceneEdge(
        String id,
        String sourceId,
        String targetId,
        String relationType,
        double relevance,
        String relationCategory,
        double sourceX,
        double sourceY,
        double targetX,
        double targetY,
        DiagramRelationMetadata metadata) {
    public DiagramSceneEdge(String id, String sourceId, String targetId, String relationType,
            double relevance, String relationCategory, double sourceX, double sourceY, double targetX, double targetY) {
        this(id, sourceId, targetId, relationType, relevance, relationCategory, sourceX, sourceY, targetX, targetY, null);
    }
}
