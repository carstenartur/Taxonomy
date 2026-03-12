package com.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * A structured trace explaining why a specific taxonomy element was
 * selected or proposed during architecture analysis.
 *
 * <p>Unlike free-text reasoning strings, this DTO provides machine-readable
 * explanation components so the UI can present a detailed score breakdown.</p>
 */
public class ExplanationTrace {

    private List<String> matchedKeywords = new ArrayList<>();
    private double semanticScore;
    private List<String> relationPath = new ArrayList<>();
    private List<ScoreComponent> scoreBreakdown = new ArrayList<>();
    private String taxonomyRoot;
    private String summaryText;

    public ExplanationTrace() {}

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public List<String> getMatchedKeywords() { return matchedKeywords; }
    public void setMatchedKeywords(List<String> matchedKeywords) { this.matchedKeywords = matchedKeywords; }

    public double getSemanticScore() { return semanticScore; }
    public void setSemanticScore(double semanticScore) { this.semanticScore = semanticScore; }

    public List<String> getRelationPath() { return relationPath; }
    public void setRelationPath(List<String> relationPath) { this.relationPath = relationPath; }

    public List<ScoreComponent> getScoreBreakdown() { return scoreBreakdown; }
    public void setScoreBreakdown(List<ScoreComponent> scoreBreakdown) { this.scoreBreakdown = scoreBreakdown; }

    public String getTaxonomyRoot() { return taxonomyRoot; }
    public void setTaxonomyRoot(String taxonomyRoot) { this.taxonomyRoot = taxonomyRoot; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    /**
     * A single component contributing to the overall score.
     */
    public static class ScoreComponent {
        private String factor;
        private double weight;
        private double value;

        public ScoreComponent() {}

        public ScoreComponent(String factor, double weight, double value) {
            this.factor = factor;
            this.weight = weight;
            this.value = value;
        }

        public String getFactor() { return factor; }
        public void setFactor(String factor) { this.factor = factor; }

        public double getWeight() { return weight; }
        public void setWeight(double weight) { this.weight = weight; }

        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
    }
}
