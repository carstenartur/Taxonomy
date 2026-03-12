package com.taxonomy.dto;

/**
 * A suggested relation that would fill an identified gap in the architecture.
 */
public class SuggestedRelation {

    private String sourceCode;
    private String targetCode;
    private String relationType;
    private String reasoning;

    public SuggestedRelation() {}

    public SuggestedRelation(String sourceCode, String targetCode,
                             String relationType, String reasoning) {
        this.sourceCode = sourceCode;
        this.targetCode = targetCode;
        this.relationType = relationType;
        this.reasoning = reasoning;
    }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getTargetCode() { return targetCode; }
    public void setTargetCode(String targetCode) { this.targetCode = targetCode; }

    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }

    public String getReasoning() { return reasoning; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
}
