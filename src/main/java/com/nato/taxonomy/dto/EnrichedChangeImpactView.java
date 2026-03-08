package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Enriched failure/change impact view that correlates affected elements
 * with their requirement coverage data.
 *
 * <p>Extends the standard {@link ChangeImpactView} data with requirement
 * information for each affected element and an aggregated risk score.
 */
public class EnrichedChangeImpactView {

    private String failedNodeCode;
    private int maxHops;
    private List<EnrichedImpactElement> directlyAffected = new ArrayList<>();
    private List<EnrichedImpactElement> indirectlyAffected = new ArrayList<>();
    private List<ImpactRelationship> traversedRelationships = new ArrayList<>();
    private List<String> affectedRequirements = new ArrayList<>();
    private double riskScore;
    private int totalAffected;
    private int totalRelationships;
    private List<String> notes = new ArrayList<>();

    public EnrichedChangeImpactView() {}

    public String getFailedNodeCode() { return failedNodeCode; }
    public void setFailedNodeCode(String failedNodeCode) { this.failedNodeCode = failedNodeCode; }

    public int getMaxHops() { return maxHops; }
    public void setMaxHops(int maxHops) { this.maxHops = maxHops; }

    public List<EnrichedImpactElement> getDirectlyAffected() { return directlyAffected; }
    public void setDirectlyAffected(List<EnrichedImpactElement> directlyAffected) { this.directlyAffected = directlyAffected; }

    public List<EnrichedImpactElement> getIndirectlyAffected() { return indirectlyAffected; }
    public void setIndirectlyAffected(List<EnrichedImpactElement> indirectlyAffected) { this.indirectlyAffected = indirectlyAffected; }

    public List<ImpactRelationship> getTraversedRelationships() { return traversedRelationships; }
    public void setTraversedRelationships(List<ImpactRelationship> traversedRelationships) { this.traversedRelationships = traversedRelationships; }

    public List<String> getAffectedRequirements() { return affectedRequirements; }
    public void setAffectedRequirements(List<String> affectedRequirements) { this.affectedRequirements = affectedRequirements; }

    public double getRiskScore() { return riskScore; }
    public void setRiskScore(double riskScore) { this.riskScore = riskScore; }

    public int getTotalAffected() { return totalAffected; }
    public void setTotalAffected(int totalAffected) { this.totalAffected = totalAffected; }

    public int getTotalRelationships() { return totalRelationships; }
    public void setTotalRelationships(int totalRelationships) { this.totalRelationships = totalRelationships; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
