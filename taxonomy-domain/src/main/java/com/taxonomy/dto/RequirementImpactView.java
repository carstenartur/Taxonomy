package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a requirement impact query.
 * Contains all elements and relationships affected by a given business requirement,
 * discovered through anchor selection and graph traversal over accepted relations.
 */
public class RequirementImpactView {

    private String businessText;
    private int maxHops;
    private List<ImpactElement> impactedElements = new ArrayList<>();
    private List<ImpactRelationship> traversedRelationships = new ArrayList<>();
    private List<String> notes = new ArrayList<>();
    private int totalElements;
    private int totalRelationships;

    public RequirementImpactView() {}

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public int getMaxHops() { return maxHops; }
    public void setMaxHops(int maxHops) { this.maxHops = maxHops; }

    public List<ImpactElement> getImpactedElements() { return impactedElements; }
    public void setImpactedElements(List<ImpactElement> impactedElements) { this.impactedElements = impactedElements; }

    public List<ImpactRelationship> getTraversedRelationships() { return traversedRelationships; }
    public void setTraversedRelationships(List<ImpactRelationship> traversedRelationships) { this.traversedRelationships = traversedRelationships; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }

    public int getTotalElements() { return totalElements; }
    public void setTotalElements(int totalElements) { this.totalElements = totalElements; }

    public int getTotalRelationships() { return totalRelationships; }
    public void setTotalRelationships(int totalRelationships) { this.totalRelationships = totalRelationships; }
}
