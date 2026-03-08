package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an architecture gap analysis.
 * Identifies missing relations and incomplete architectural patterns
 * based on the {@link com.nato.taxonomy.service.RelationCompatibilityMatrix}.
 */
public class GapAnalysisView {

    private String businessText;
    private List<MissingRelation> missingRelations = new ArrayList<>();
    private List<IncompletePattern> incompletePatterns = new ArrayList<>();
    private List<CoverageGap> coverageGaps = new ArrayList<>();
    private int totalAnchors;
    private int totalGaps;
    private List<String> notes = new ArrayList<>();

    public GapAnalysisView() {}

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public List<MissingRelation> getMissingRelations() { return missingRelations; }
    public void setMissingRelations(List<MissingRelation> missingRelations) { this.missingRelations = missingRelations; }

    public List<IncompletePattern> getIncompletePatterns() { return incompletePatterns; }
    public void setIncompletePatterns(List<IncompletePattern> incompletePatterns) { this.incompletePatterns = incompletePatterns; }

    public List<CoverageGap> getCoverageGaps() { return coverageGaps; }
    public void setCoverageGaps(List<CoverageGap> coverageGaps) { this.coverageGaps = coverageGaps; }

    public int getTotalAnchors() { return totalAnchors; }
    public void setTotalAnchors(int totalAnchors) { this.totalAnchors = totalAnchors; }

    public int getTotalGaps() { return totalGaps; }
    public void setTotalGaps(int totalGaps) { this.totalGaps = totalGaps; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
