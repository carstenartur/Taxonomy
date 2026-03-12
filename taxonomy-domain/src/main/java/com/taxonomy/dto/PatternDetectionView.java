package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of architecture pattern detection.
 * Identifies which standard architectural patterns are present, partially
 * present, or missing in the analysed set of nodes.
 */
public class PatternDetectionView {

    private String nodeCode;
    private List<DetectedPattern> matchedPatterns = new ArrayList<>();
    private List<DetectedPattern> incompletePatterns = new ArrayList<>();
    private double patternCoverage;
    private List<String> notes = new ArrayList<>();

    public PatternDetectionView() {}

    public String getNodeCode() { return nodeCode; }
    public void setNodeCode(String nodeCode) { this.nodeCode = nodeCode; }

    public List<DetectedPattern> getMatchedPatterns() { return matchedPatterns; }
    public void setMatchedPatterns(List<DetectedPattern> matchedPatterns) { this.matchedPatterns = matchedPatterns; }

    public List<DetectedPattern> getIncompletePatterns() { return incompletePatterns; }
    public void setIncompletePatterns(List<DetectedPattern> incompletePatterns) { this.incompletePatterns = incompletePatterns; }

    public double getPatternCoverage() { return patternCoverage; }
    public void setPatternCoverage(double patternCoverage) { this.patternCoverage = patternCoverage; }

    public List<String> getNotes() { return notes; }
    public void setNotes(List<String> notes) { this.notes = notes; }
}
