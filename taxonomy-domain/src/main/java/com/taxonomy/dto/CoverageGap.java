package com.taxonomy.dto;

/**
 * Identifies a node that has requirement coverage but lacks expected
 * architectural neighbours (relations) according to the compatibility matrix.
 */
public class CoverageGap {

    private String nodeCode;
    private String taxonomyRoot;
    private int coverageScore;
    private String gapDescription;

    public CoverageGap() {}

    public CoverageGap(String nodeCode, String taxonomyRoot, int coverageScore, String gapDescription) {
        this.nodeCode = nodeCode;
        this.taxonomyRoot = taxonomyRoot;
        this.coverageScore = coverageScore;
        this.gapDescription = gapDescription;
    }

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public String getTaxonomyRoot() { return taxonomyRoot; }
    public void setTaxonomyRoot(String taxonomyRoot) { this.taxonomyRoot = taxonomyRoot; }

    public int getCoverageScore() { return coverageScore; }
    public void setCoverageScore(int coverageScore) { this.coverageScore = coverageScore; }

    public String getGapDescription() { return gapDescription; }
    public void setGapDescription(String gapDescription) { this.gapDescription = gapDescription; }
}
