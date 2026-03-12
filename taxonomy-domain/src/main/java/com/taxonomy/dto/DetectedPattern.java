package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single detected (or partially detected) architecture pattern.
 */
public class DetectedPattern {

    private String patternName;
    private List<String> expectedSteps = new ArrayList<>();
    private List<String> presentSteps = new ArrayList<>();
    private List<String> missingSteps = new ArrayList<>();
    private double completeness;

    public DetectedPattern() {}

    public DetectedPattern(String patternName, List<String> expectedSteps,
                           List<String> presentSteps, List<String> missingSteps,
                           double completeness) {
        this.patternName = patternName;
        this.expectedSteps = expectedSteps;
        this.presentSteps = presentSteps;
        this.missingSteps = missingSteps;
        this.completeness = completeness;
    }

    public String getPatternName() { return patternName; }
    public void setPatternName(String patternName) { this.patternName = patternName; }

    public List<String> getExpectedSteps() { return expectedSteps; }
    public void setExpectedSteps(List<String> expectedSteps) { this.expectedSteps = expectedSteps; }

    public List<String> getPresentSteps() { return presentSteps; }
    public void setPresentSteps(List<String> presentSteps) { this.presentSteps = presentSteps; }

    public List<String> getMissingSteps() { return missingSteps; }
    public void setMissingSteps(List<String> missingSteps) { this.missingSteps = missingSteps; }

    public double getCompleteness() { return completeness; }
    public void setCompleteness(double completeness) { this.completeness = completeness; }
}
