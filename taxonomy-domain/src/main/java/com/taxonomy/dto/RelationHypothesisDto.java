package com.taxonomy.dto;

/**
 * A provisional relation hypothesis generated during analysis.
 *
 * <p>After persistence {@link #hypothesisId} identifies the exact tenant-scoped
 * review record. This lets browser clients review the hypothesis directly
 * instead of creating a second proposal record with duplicated semantics.</p>
 */
public class RelationHypothesisDto {

    private Long hypothesisId;
    private String sourceCode;
    private String sourceName;
    private String targetCode;
    private String targetName;
    private String relationType;
    private double confidence;
    private String reasoning;

    public RelationHypothesisDto() {}

    public RelationHypothesisDto(String sourceCode, String sourceName,
                                  String targetCode, String targetName,
                                  String relationType, double confidence,
                                  String reasoning) {
        this.sourceCode = sourceCode;
        this.sourceName = sourceName;
        this.targetCode = targetCode;
        this.targetName = targetName;
        this.relationType = relationType;
        this.confidence = confidence;
        this.reasoning = reasoning;
    }

    public Long getHypothesisId() { return hypothesisId; }
    public void setHypothesisId(Long hypothesisId) {
        this.hypothesisId = hypothesisId;
    }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getTargetCode() { return targetCode; }
    public void setTargetCode(String targetCode) { this.targetCode = targetCode; }

    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }

    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
}
