package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Extends {@link ImpactElement} with requirement coverage information.
 * Each element carries a list of requirements that cover it.
 */
public class EnrichedImpactElement {

    private String nodeCode;
    private String title;
    private String taxonomySheet;
    private double relevance;
    private int hopDistance;
    private String includedBecause;
    private List<String> coveredByRequirements = new ArrayList<>();
    private int requirementCount;

    public EnrichedImpactElement() {}

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

    public List<String> getCoveredByRequirements() { return coveredByRequirements; }
    public void setCoveredByRequirements(List<String> coveredByRequirements) { this.coveredByRequirements = coveredByRequirements; }

    public int getRequirementCount() { return requirementCount; }
    public void setRequirementCount(int requirementCount) { this.requirementCount = requirementCount; }
}
