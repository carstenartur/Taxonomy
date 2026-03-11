package com.nato.taxonomy.dto;

/**
 * A provisional (not-yet-persisted) relation hypothesis generated during analysis.
 * Represents an AI-suggested relationship between two scored taxonomy nodes.
 */
public class RelationHypothesisDto {

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
