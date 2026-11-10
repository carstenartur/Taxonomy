package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/** Relevance-ranked matches, explicit canonical membership and proposals from violated scoped obligations. */
public class ArchitectureRecommendation {

    private String businessText;
    private List<RecommendedElement> acceptedElements = new ArrayList<>();
    private GapAnalysisView gapAnalysis;
    public List<RecommendedElement> getAcceptedElements() { return acceptedElements; }
    public void setAcceptedElements(List<RecommendedElement> value) { acceptedElements = value; }
    public GapAnalysisView getGapAnalysis() { return gapAnalysis; }
    public void setGapAnalysis(GapAnalysisView value) { gapAnalysis = value; }
    private List<RecommendedElement> matchedElements = new ArrayList<>();
    private List<RecommendedElement> proposedElements = new ArrayList<>();
    private List<SuggestedRelation> suggestedRelations = new ArrayList<>();
    private Double meanRelevance;
    private List<String> reasoning = new ArrayList<>();
    private List<String> notes = new ArrayList<>();

    public ArchitectureRecommendation() {}

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public List<RecommendedElement> getMatchedElements() { return matchedElements; }
    public void setMatchedElements(List<RecommendedElement> matchedElements) { this.matchedElements = matchedElements; }

    public List<RecommendedElement> getProposedElements() { return proposedElements; }
    public void setProposedElements(List<RecommendedElement> proposedElements) { this.proposedElements = proposedElements; }

    public List<SuggestedRelation> getSuggestedRelations() { return suggestedRelations; }
    public void setSuggestedRelations(List<SuggestedRelation> suggestedRelations) { this.suggestedRelations = suggestedRelations; }

    public Double getMeanRelevance() { return meanRelevance; }
    public void setMeanRelevance(Double meanRelevance) { this.meanRelevance = meanRelevance; }

    public List<String> getReasoning() { return reasoning; }
    public void setReasoning(List<String> reasoning) { this.reasoning = reasoning; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
