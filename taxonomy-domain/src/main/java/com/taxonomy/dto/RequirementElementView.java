package com.taxonomy.dto;

public class RequirementElementView {

    private String nodeCode;
    private String title;
    private String taxonomySheet;
    private double relevance;
    private int hopDistance;
    private boolean anchor;
    private String includedBecause;
    /** Full hierarchy path from root to this node (e.g. "CP &gt; CP-1000 &gt; CP-1023"). */
    private String hierarchyPath;

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

    public String getHierarchyPath() { return hierarchyPath; }
    public void setHierarchyPath(String hierarchyPath) { this.hierarchyPath = hierarchyPath; }
}
