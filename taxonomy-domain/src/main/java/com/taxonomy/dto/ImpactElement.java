package com.taxonomy.dto;

/**
 * Represents a single element included in an impact or neighborhood view.
 */
public class ImpactElement {

    private String nodeCode;
    private String title;
    private String taxonomySheet;
    private double relevance;
    private int hopDistance;
    private String includedBecause;

    public ImpactElement() {}

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

    public String getIncludedBecause() { return includedBecause; }
    public void setIncludedBecause(String includedBecause) { this.includedBecause = includedBecause; }
}
