package com.taxonomy.dto;

public class RequirementElementView {

    private String nodeCode;
    private String title;
    private String taxonomySheet;
    private double relevance;
    private int hopDistance;
    private boolean anchor;
    private String includedBecause;

    public RequirementElementView() {}

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getTaxonomySheet() { return taxonomySheet; }
    public void setTaxonomySheet(String taxonomySheet) { this.taxonomySheet = taxonomySheet; }

    public double getRelevance() { return relevance; }
    public void setRelevance(double relevance) { this.relevance = relevance; }

    public int getHopDistance() { return hopDistance; }
    public void setHopDistance(int hopDistance) { this.hopDistance = hopDistance; }

    public boolean isAnchor() { return anchor; }
    public void setAnchor(boolean anchor) { this.anchor = anchor; }

    public String getIncludedBecause() { return includedBecause; }
    public void setIncludedBecause(String includedBecause) { this.includedBecause = includedBecause; }
}
