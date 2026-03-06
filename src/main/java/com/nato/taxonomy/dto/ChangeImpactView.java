package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a failure / change impact query.
 * Contains directly and indirectly affected elements when a given node
 * fails or changes, discovered via directed graph traversal over accepted relations.
 */
public class ChangeImpactView {

    private String failedNodeCode;
    private int maxHops;
    private List<ImpactElement> directlyAffected = new ArrayList<>();
    private List<ImpactElement> indirectlyAffected = new ArrayList<>();
    private List<ImpactRelationship> traversedRelationships = new ArrayList<>();
    private List<String> notes = new ArrayList<>();
    private int totalAffected;
    private int totalRelationships;

    public ChangeImpactView() {}

    public String getFailedNodeCode() { return failedNodeCode; }
    public void setFailedNodeCode(String failedNodeCode) { this.failedNodeCode = failedNodeCode; }

    public int getMaxHops() { return maxHops; }
    public void setMaxHops(int maxHops) { this.maxHops = maxHops; }

    public List<ImpactElement> getDirectlyAffected() { return directlyAffected; }
    public void setDirectlyAffected(List<ImpactElement> directlyAffected) { this.directlyAffected = directlyAffected; }

    public List<ImpactElement> getIndirectlyAffected() { return indirectlyAffected; }
    public void setIndirectlyAffected(List<ImpactElement> indirectlyAffected) { this.indirectlyAffected = indirectlyAffected; }

    public List<ImpactRelationship> getTraversedRelationships() { return traversedRelationships; }
    public void setTraversedRelationships(List<ImpactRelationship> traversedRelationships) { this.traversedRelationships = traversedRelationships; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }

    public int getTotalAffected() { return totalAffected; }
    public void setTotalAffected(int totalAffected) { this.totalAffected = totalAffected; }

    public int getTotalRelationships() { return totalRelationships; }
    public void setTotalRelationships(int totalRelationships) { this.totalRelationships = totalRelationships; }
}
