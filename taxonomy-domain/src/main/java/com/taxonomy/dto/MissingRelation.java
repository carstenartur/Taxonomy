package com.taxonomy.dto;

/**
 * Represents an expected but missing relation discovered during gap analysis.
 * The compatibility matrix says this source root should have this relation type
 * to a target root, but no such relation exists in the repository.
 */
public class MissingRelation {

    private String sourceNodeCode;
    private String sourceRoot;
    private String expectedRelationType;
    private String expectedTargetRoot;
    private String description;

    public MissingRelation() {}

    public MissingRelation(String sourceNodeCode, String sourceRoot,
                           String expectedRelationType, String expectedTargetRoot,
                           String description) {
        this.sourceNodeCode = sourceNodeCode;
        this.sourceRoot = sourceRoot;
        this.expectedRelationType = expectedRelationType;
        this.expectedTargetRoot = expectedTargetRoot;
        this.description = description;
    }

    public String getSourceNodeCode() { return sourceNodeCode; }
    public void setSourceNodeCode(String sourceNodeCode) { this.sourceNodeCode = sourceNodeCode; }

    public String getSourceRoot() { return sourceRoot; }
    public void setSourceRoot(String sourceRoot) { this.sourceRoot = sourceRoot; }

    public String getExpectedRelationType() { return expectedRelationType; }
    public void setExpectedRelationType(String expectedRelationType) { this.expectedRelationType = expectedRelationType; }

    public String getExpectedTargetRoot() { return expectedTargetRoot; }
    public void setExpectedTargetRoot(String expectedTargetRoot) { this.expectedTargetRoot = expectedTargetRoot; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
