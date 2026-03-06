package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an upstream or downstream neighborhood query.
 * Contains all elements and relationships found by traversing incoming (upstream)
 * or outgoing (downstream) edges from a given node.
 */
public class GraphNeighborhoodView {

    private String originNodeCode;
    private String direction; // "UPSTREAM" or "DOWNSTREAM"
    private int maxHops;
    private List<ImpactElement> neighbors = new ArrayList<>();
    private List<ImpactRelationship> traversedRelationships = new ArrayList<>();
    private List<String> notes = new ArrayList<>();
    private int totalNeighbors;
    private int totalRelationships;

    public GraphNeighborhoodView() {}

    public String getOriginNodeCode() { return originNodeCode; }
    public void setOriginNodeCode(String originNodeCode) { this.originNodeCode = originNodeCode; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public int getMaxHops() { return maxHops; }
    public void setMaxHops(int maxHops) { this.maxHops = maxHops; }

    public List<ImpactElement> getNeighbors() { return neighbors; }
    public void setNeighbors(List<ImpactElement> neighbors) { this.neighbors = neighbors; }

    public List<ImpactRelationship> getTraversedRelationships() { return traversedRelationships; }
    public void setTraversedRelationships(List<ImpactRelationship> traversedRelationships) { this.traversedRelationships = traversedRelationships; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }

    public int getTotalNeighbors() { return totalNeighbors; }
    public void setTotalNeighbors(int totalNeighbors) { this.totalNeighbors = totalNeighbors; }

    public int getTotalRelationships() { return totalRelationships; }
    public void setTotalRelationships(int totalRelationships) { this.totalRelationships = totalRelationships; }
}
