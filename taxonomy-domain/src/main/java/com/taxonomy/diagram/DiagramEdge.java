package com.taxonomy.diagram;

public record DiagramEdge(
    String id,
    String sourceId,
    String targetId,
    String relationType,
    double relevance,
    String relationCategory
) {
    /**
     * Backward-compatible constructor that defaults relationCategory to "trace".
     */
    public DiagramEdge(String id, String sourceId, String targetId,
                       String relationType, double relevance) {
        this(id, sourceId, targetId, relationType, relevance, "trace");
    }
}
