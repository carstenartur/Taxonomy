package com.taxonomy.analysis.usecase;

import java.util.Map;

/** Stable response contract for one interactive child-node evaluation. */
public record AnalyzeNodeChildrenResult(
        Map<String, Double> scores,
        Map<String, String> reasons,
        String prompt,
        String rawResponse,
        String provider,
        long durationMs,
        String error,
        String analysisOperationId,
        String analysisProviderId) {

    public AnalyzeNodeChildrenResult {
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
        prompt = prompt == null ? "" : prompt;
        rawResponse = rawResponse == null ? "" : rawResponse;
        provider = provider == null ? "" : provider;
    }

    public AnalyzeNodeChildrenResult(Map<String, Double> scores, Map<String, String> reasons, String prompt,
                                     String rawResponse, String provider, long durationMs, String error) {
        this(scores, reasons, prompt, rawResponse, provider, durationMs, error, null, null);
    }

    public AnalyzeNodeChildrenResult withFollowUpContext(String operationId, String providerId) {
        return new AnalyzeNodeChildrenResult(scores, reasons, prompt, rawResponse, provider, durationMs, error,
                operationId, providerId);
    }

    public static AnalyzeNodeChildrenResult empty() {
        return new AnalyzeNodeChildrenResult(
                Map.of(), Map.of(), "", "", "", 0L, null);
    }
}
