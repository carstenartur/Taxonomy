package com.taxonomy.dto;

import java.util.Map;

/**
 * Captures details of a single LLM API call: the prompt sent, the raw text response,
 * parsed scores, reasons per node, provider name, call duration, and any discrepancy.
 */
public class LlmCallDetail {

    private Map<String, Double> scores;
    private Map<String, String> reasons;
    private String prompt;
    private String rawResponse;
    private String provider;
    private long durationMs;
    private String error;
    private TaxonomyDiscrepancy discrepancy;
    private Map<String, RootAssessment> rootAssessments = Map.of();

    public LlmCallDetail() {}

    public Map<String, Double> getScores() { return scores; }
    public void setScores(Map<String, ? extends Number> scores) { this.scores = scores == null ? null : new java.util.LinkedHashMap<>(AnalysisScoreSemantics.normalizeScores(scores)); }

    public Map<String, String> getReasons() { return reasons; }
    public void setReasons(Map<String, String> reasons) { this.reasons = reasons; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getRawResponse() { return rawResponse; }
    public void setRawResponse(String rawResponse) { this.rawResponse = rawResponse; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public TaxonomyDiscrepancy getDiscrepancy() { return discrepancy; }
    public void setDiscrepancy(TaxonomyDiscrepancy discrepancy) { this.discrepancy = discrepancy; }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public Map<String, RootAssessment> getRootAssessments() { return rootAssessments; }

    public void setRootAssessments(Map<String, RootAssessment> values) {
        if (values == null || values.isEmpty()) { rootAssessments = Map.of(); return; }
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getKey().length() > 256 || entry.getValue() == null))
            throw new IllegalArgumentException("Root assessment evidence requires bounded identities and valid decisions");
        rootAssessments = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(values));
    }
}
