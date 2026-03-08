package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an architecture recommendation analysis.
 * Combines confirmed matches, gap analysis, and proposed elements
 * to create a comprehensive architectural recommendation.
 */
public class ArchitectureRecommendation {

    private String businessText;
    private List<RecommendedElement> confirmedElements = new ArrayList<>();
    private List<RecommendedElement> proposedElements = new ArrayList<>();
    private List<SuggestedRelation> suggestedRelations = new ArrayList<>();
    private double confidence;
    private List<String> reasoning = new ArrayList<>();
    private List<String> notes = new ArrayList<>();

    public ArchitectureRecommendation() {}

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public List<RecommendedElement> getConfirmedElements() { return confirmedElements; }
    public void setConfirmedElements(List<RecommendedElement> confirmedElements) { this.confirmedElements = confirmedElements; }

    public List<RecommendedElement> getProposedElements() { return proposedElements; }
    public void setProposedElements(List<RecommendedElement> proposedElements) { this.proposedElements = proposedElements; }

    public List<SuggestedRelation> getSuggestedRelations() { return suggestedRelations; }
    public void setSuggestedRelations(List<SuggestedRelation> suggestedRelations) { this.suggestedRelations = suggestedRelations; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public List<String> getReasoning() { return reasoning; }
    public void setReasoning(List<String> reasoning) { this.reasoning = reasoning; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
