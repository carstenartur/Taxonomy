package com.taxonomy.dto;

/**
 * Represents a relationship traversed in an impact or neighborhood query.
 */
public class ImpactRelationship {

    private Long relationId;
    private String sourceCode;
    private String targetCode;
    private String relationType;
    private double propagatedRelevance;
    private int hopDistance;

    public ImpactRelationship() {}

    public Long getRelationId() { return relationId; }
    public void setRelationId(Long relationId) { this.relationId = relationId; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getTargetCode() { return targetCode; }
    public void setTargetCode(String targetCode) { this.targetCode = targetCode; }

    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }

    public double getPropagatedRelevance() { return propagatedRelevance; }
    public void setPropagatedRelevance(double propagatedRelevance) { this.propagatedRelevance = propagatedRelevance; }

    public int getHopDistance() { return hopDistance; }
    public void setHopDistance(int hopDistance) { this.hopDistance = hopDistance; }
}
