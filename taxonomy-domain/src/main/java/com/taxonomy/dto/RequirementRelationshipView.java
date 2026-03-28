package com.taxonomy.dto;

public class RequirementRelationshipView {

    /** Relation is included for scoring traceability (typically root-level propagation). */
    public static final String CATEGORY_TRACE = "trace";

    /** Relation represents a concrete cross-category architecture impact (typically leaf-to-leaf). */
    public static final String CATEGORY_IMPACT = "impact";

    private Long relationId;
    private String sourceCode;
    private String targetCode;
    private String relationType;
    private double propagatedRelevance;
    private int hopDistance;
    private String includedBecause;
    private String relationCategory = CATEGORY_TRACE;

    public RequirementRelationshipView() {}

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

    public String getIncludedBecause() { return includedBecause; }
    public void setIncludedBecause(String includedBecause) { this.includedBecause = includedBecause; }

    public String getRelationCategory() { return relationCategory; }
    public void setRelationCategory(String relationCategory) { this.relationCategory = relationCategory; }
}
