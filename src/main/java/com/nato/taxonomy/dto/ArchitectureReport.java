package com.nato.taxonomy.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bundles all architecture analysis results into a single exportable report.
 * This DTO aggregates data from existing services:
 * {@link RequirementArchitectureView}, {@link GapAnalysisView},
 * {@link PatternDetectionView}, {@link ArchitectureRecommendation},
 * and provisional {@link RelationProposalDto}s.
 */
public class ArchitectureReport {

    private String businessText;
    private Instant generatedAt;
    private Map<String, Integer> scores;

    private RequirementArchitectureView architectureView;
    private GapAnalysisView gapAnalysis;
    private PatternDetectionView patternDetection;
    private ArchitectureRecommendation recommendation;
    private List<RelationProposalDto> pendingProposals = new ArrayList<>();
    private String mermaidDiagram;

    public ArchitectureReport() {
        this.generatedAt = Instant.now();
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public Map<String, Integer> getScores() { return scores; }
    public void setScores(Map<String, Integer> scores) { this.scores = scores; }

    public RequirementArchitectureView getArchitectureView() { return architectureView; }
    public void setArchitectureView(RequirementArchitectureView architectureView) { this.architectureView = architectureView; }

    public GapAnalysisView getGapAnalysis() { return gapAnalysis; }
    public void setGapAnalysis(GapAnalysisView gapAnalysis) { this.gapAnalysis = gapAnalysis; }

    public PatternDetectionView getPatternDetection() { return patternDetection; }
    public void setPatternDetection(PatternDetectionView patternDetection) { this.patternDetection = patternDetection; }

    public ArchitectureRecommendation getRecommendation() { return recommendation; }
    public void setRecommendation(ArchitectureRecommendation recommendation) { this.recommendation = recommendation; }

    public List<RelationProposalDto> getPendingProposals() { return pendingProposals; }
    public void setPendingProposals(List<RelationProposalDto> pendingProposals) { this.pendingProposals = pendingProposals; }

    public String getMermaidDiagram() { return mermaidDiagram; }
    public void setMermaidDiagram(String mermaidDiagram) { this.mermaidDiagram = mermaidDiagram; }
}
