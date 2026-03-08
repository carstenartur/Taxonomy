package com.nato.taxonomy.dto;

/**
 * Describes an incomplete architectural pattern.
 * For example, "Capability CP-3 has no realizing Service".
 */
public class IncompletePattern {

    private String nodeCode;
    private String taxonomyRoot;
    private String patternDescription;
    private String missingElement;

    public IncompletePattern() {}

    public IncompletePattern(String nodeCode, String taxonomyRoot,
                             String patternDescription, String missingElement) {
        this.nodeCode = nodeCode;
        this.taxonomyRoot = taxonomyRoot;
        this.patternDescription = patternDescription;
        this.missingElement = missingElement;
    }

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public String getTaxonomyRoot() { return taxonomyRoot; }
    public void setTaxonomyRoot(String taxonomyRoot) { this.taxonomyRoot = taxonomyRoot; }

    public String getPatternDescription() { return patternDescription; }
    public void setPatternDescription(String patternDescription) { this.patternDescription = patternDescription; }

    public String getMissingElement() { return missingElement; }
    public void setMissingElement(String missingElement) { this.missingElement = missingElement; }
}
