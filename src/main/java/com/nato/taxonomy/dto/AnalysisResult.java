package com.nato.taxonomy.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnalysisResult {

    private Map<String, Integer> scores;
    private List<TaxonomyNodeDto> tree;

    /** "SUCCESS", "PARTIAL", or "ERROR" */
    private String status;

    /** Accumulated warning messages (e.g. which roots were skipped). */
    private List<String> warnings = new ArrayList<>();

    /** Human-readable error/partial message; set when status is PARTIAL or ERROR. */
    private String errorMessage;

    public AnalysisResult() {}

    public AnalysisResult(Map<String, Integer> scores, List<TaxonomyNodeDto> tree) {
        this.scores = scores;
        this.tree = tree;
    }

    public Map<String, Integer> getScores() { return scores; }
    public void setScores(Map<String, Integer> scores) { this.scores = scores; }

    public List<TaxonomyNodeDto> getTree() { return tree; }
    public void setTree(List<TaxonomyNodeDto> tree) { this.tree = tree; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
