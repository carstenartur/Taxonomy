package com.taxonomy.diagram;

public record DiagramEdge(
    String id,
    String sourceId,
    String targetId,
    String relationType,
    double relevance,
    String relationCategory,
    DiagramRelationMetadata metadata
) {
    public DiagramEdge(String id, String sourceId, String targetId, String relationType,
                       double relevance, String relationCategory) {
        this(id, sourceId, targetId, relationType, relevance, relationCategory, null);
    }

    /**
     * Backward-compatible constructor that defaults relationCategory to "trace".
     */
    public DiagramEdge(String id, String sourceId, String targetId,
                       String relationType, double relevance) {
        this(id, sourceId, targetId, relationType, relevance, "trace");
    }
}
