package com.taxonomy.dto;

import java.util.List;
import java.util.Map;

/**
 * DTO representing a saved (exported/imported) taxonomy analysis result.
 *
 * <p>Semantic distinction:
 * <ul>
 *   <li>A node code <b>present with value {@code 0}</b> → the node was <b>evaluated and scored 0%</b> (not relevant)</li>
 *   <li>A node code <b>absent / {@code null}</b> → the node was <b>not yet evaluated</b></li>
 * </ul>
 *
 * <p>Version history:
 * <ul>
 *   <li><b>Version 1</b>: Original format with requirement, scores, reasons</li>
 *   <li><b>Version 2</b>: Adds optional provenance data (sources, sourceVersions, sourceFragments, requirementSourceLinks)</li>
 *   <li><b>Version 3</b>: Requires raw scores, own assessment/subtree coverage and analysis status; older readers reject it rather than silently losing unknowns.</li>
 * </ul>
 */
public class SavedAnalysis {

    /** Format version — {@code 1} for legacy, {@code 2} for provenance support. */
    private int version = 2;

    /** The business requirement text that was analyzed. */
    private String requirement;

    /** ISO 8601 timestamp of when the analysis was performed or exported. */
    private String timestamp;

    /** Name of the LLM provider that generated these scores (informational). */
    private String provider;

    /**
     * Node code → score (0–100).
     * {@code 0} means the node was evaluated but scored zero (not relevant).
     * An absent key means the node was not evaluated at all.
     */
    private Map<String, Integer> scores;

    /**
     * Node code → reason text (optional, may be sparse).
     */
    private Map<String, String> reasons;

    /** Source artifacts referenced by this analysis (optional, may be null). */
    private List<SourceArtifactDto> sources;

    /** Source versions (optional, may be null). */
    private List<SourceVersionDto> sourceVersions;

    /** Source fragments (optional, may be null). */
    private List<SourceFragmentDto> sourceFragments;

    /** Requirement-to-source links (optional, may be null). */
    private List<RequirementSourceLinkDto> requirementSourceLinks;

    private AnalysisCoverage analysisCoverage;
    private String analysisStatus;
    private Map<String, Integer> rawScores;
    public AnalysisCoverage getAnalysisCoverage() { return analysisCoverage; }
    public void setAnalysisCoverage(AnalysisCoverage value) { analysisCoverage = value; }
    public String getAnalysisStatus() { return analysisStatus; }
    public void setAnalysisStatus(String value) { analysisStatus = value; }
    public Map<String, Integer> getRawScores() { return rawScores; }
    public void setRawScores(Map<String, Integer> value) { rawScores = value; }

    /** Validate version 3 before publishing or consuming a score file. */
    public void validateCoverageEvidence() {
        if (this.getVersion() >= 3) {
            var coverage = this.getAnalysisCoverage(); var raw = this.getRawScores();
            if (coverage == null || raw == null || this.getAnalysisStatus() == null
                    || this.getAnalysisStatus().isBlank() || raw.size() > 25000
                    || !raw.keySet().containsAll(this.getScores().keySet()))
                throw new IllegalArgumentException("Version 3 requires complete raw evidence, status and coverage metadata");
            for (var entry : raw.entrySet()) {
                var assessment = coverage.nodes().get(entry.getKey());
                if (assessment == null || assessment.state() == com.taxonomy.dto.AnalysisCoverage.State.UNKNOWN
                        || !java.util.Objects.equals(entry.getValue(), assessment.score()))
                    throw new IllegalArgumentException("Raw evidence disagrees with assessment coverage");
            }
            for (var entry : coverage.nodes().entrySet()) if (entry.getValue().state() != com.taxonomy.dto.AnalysisCoverage.State.UNKNOWN
                    && !raw.containsKey(entry.getKey()))
                throw new IllegalArgumentException("Coverage contains a completed assessment without its raw evidence");
            for (var entry : this.getScores().entrySet()) {
                var assessment = coverage.nodes().get(entry.getKey());
                if (assessment == null || !java.util.Objects.equals(entry.getValue(), assessment.effectiveRelevance()))
                    throw new IllegalArgumentException("Effective evidence disagrees with assessment coverage");
            }
            if (coverage.hasOpenEvaluations()) this.setAnalysisStatus("PARTIAL");
        }

    }

    public SavedAnalysis() {}

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public Map<String, Integer> getScores() { return scores; }
    public void setScores(Map<String, Integer> scores) { this.scores = scores; }

    public Map<String, String> getReasons() { return reasons; }
    public void setReasons(Map<String, String> reasons) { this.reasons = reasons; }

    public List<SourceArtifactDto> getSources() { return sources; }
    public void setSources(List<SourceArtifactDto> sources) { this.sources = sources; }

    public List<SourceVersionDto> getSourceVersions() { return sourceVersions; }
    public void setSourceVersions(List<SourceVersionDto> sourceVersions) { this.sourceVersions = sourceVersions; }

    public List<SourceFragmentDto> getSourceFragments() { return sourceFragments; }
    public void setSourceFragments(List<SourceFragmentDto> sourceFragments) { this.sourceFragments = sourceFragments; }

    public List<RequirementSourceLinkDto> getRequirementSourceLinks() { return requirementSourceLinks; }
    public void setRequirementSourceLinks(List<RequirementSourceLinkDto> requirementSourceLinks) { this.requirementSourceLinks = requirementSourceLinks; }
}
