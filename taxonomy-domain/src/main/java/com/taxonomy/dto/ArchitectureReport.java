package com.taxonomy.dto;

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

    /** Model, catalogue, scope and profile recorded by the retained analysis. */
    private AnalysisModelBinding modelBinding;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public AnalysisModelBinding getModelBinding() { return modelBinding; }
    public void setModelBinding(AnalysisModelBinding value) { modelBinding = value; }

    private Long requirementVersionId;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public Long getRequirementVersionId() { return requirementVersionId; }
    public void setRequirementVersionId(Long value) { requirementVersionId = value; }

    private AnalysisScope analysisScope;
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public AnalysisScope getAnalysisScope() { return analysisScope; }
    public void setAnalysisScope(AnalysisScope value) { analysisScope = value; }

    private String businessText;
    private Instant generatedAt;
    private Map<String, Double> scores;

    private RequirementArchitectureView architectureView;
    private GapAnalysisView gapAnalysis;
    private PatternDetectionView patternDetection;
    private ArchitectureRecommendation recommendation;
    /** Retained completion/partial/skipped semantics, absent for a legacy recomputed report. */
    private AnalysisCopilotResult copilot;
    private List<RelationProposalDto> pendingProposals = new ArrayList<>();
    private String mermaidDiagram;

    /** Git commit provenance and projection/index freshness metadata. */
    private ViewContext viewContext;

    public ArchitectureReport() {
        this.generatedAt = Instant.now();
    }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getBusinessText() { return businessText; }
    public void setBusinessText(String businessText) { this.businessText = businessText; }

    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }

    public Map<String, Double> getScores() { return scores; }
    public void setScores(Map<String, Double> scores) { this.scores = scores; }

    public RequirementArchitectureView getArchitectureView() { return architectureView; }
    public void setArchitectureView(RequirementArchitectureView architectureView) { this.architectureView = architectureView; }

    public GapAnalysisView getGapAnalysis() { return gapAnalysis; }
    public void setGapAnalysis(GapAnalysisView gapAnalysis) { this.gapAnalysis = gapAnalysis; }

    public PatternDetectionView getPatternDetection() { return patternDetection; }
    public void setPatternDetection(PatternDetectionView patternDetection) { this.patternDetection = patternDetection; }

    public ArchitectureRecommendation getRecommendation() { return recommendation; }
    public void setRecommendation(ArchitectureRecommendation recommendation) { this.recommendation = recommendation; }

    public AnalysisCopilotResult getCopilot() { return copilot; }
    public void setCopilot(AnalysisCopilotResult copilot) { this.copilot = copilot; }

    public List<RelationProposalDto> getPendingProposals() { return pendingProposals; }
    public void setPendingProposals(List<RelationProposalDto> pendingProposals) { this.pendingProposals = pendingProposals; }

    public String getMermaidDiagram() { return mermaidDiagram; }
    public void setMermaidDiagram(String mermaidDiagram) { this.mermaidDiagram = mermaidDiagram; }

    public ViewContext getViewContext() { return viewContext; }
    public void setViewContext(ViewContext viewContext) { this.viewContext = viewContext; }
}
