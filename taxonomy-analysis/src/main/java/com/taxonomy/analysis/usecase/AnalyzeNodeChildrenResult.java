package com.taxonomy.analysis.usecase;

import java.util.Map;
import com.taxonomy.dto.AnalysisReasonLength;

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
        String analysisProviderId,
        AnalysisReasonLength analysisReasonLength,
        com.taxonomy.dto.AnalysisLanguage analysisLanguage) {

    public AnalyzeNodeChildrenResult {
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
        prompt = prompt == null ? "" : prompt;
        rawResponse = rawResponse == null ? "" : rawResponse;
        provider = provider == null ? "" : provider;
    }

    public AnalyzeNodeChildrenResult(Map<String, Double> scores, Map<String, String> reasons, String prompt,
                                     String rawResponse, String provider, long durationMs, String error,
                                     String analysisOperationId, String analysisProviderId) {
        this(scores, reasons, prompt, rawResponse, provider, durationMs, error,
                analysisOperationId, analysisProviderId, null);
    }

    public AnalyzeNodeChildrenResult(Map<String, Double> scores, Map<String, String> reasons, String prompt,
                                     String rawResponse, String provider, long durationMs, String error,
                                     String analysisOperationId, String analysisProviderId, AnalysisReasonLength length) {
        this(scores, reasons, prompt, rawResponse, provider, durationMs, error,
                analysisOperationId, analysisProviderId, length, null);
    }

    public AnalyzeNodeChildrenResult withAnalysisLanguage(com.taxonomy.dto.AnalysisLanguage language) {
        return new AnalyzeNodeChildrenResult(scores, reasons, prompt, rawResponse, provider, durationMs, error,
                analysisOperationId, analysisProviderId, analysisReasonLength, language);
    }

    public AnalyzeNodeChildrenResult withAnalysisReasonLength(AnalysisReasonLength length) {
        return new AnalyzeNodeChildrenResult(scores, reasons, prompt, rawResponse, provider, durationMs, error,
                analysisOperationId, analysisProviderId, length, analysisLanguage);
    }

    public AnalyzeNodeChildrenResult(Map<String, Double> scores, Map<String, String> reasons, String prompt,
                                     String rawResponse, String provider, long durationMs, String error) {
        this(scores, reasons, prompt, rawResponse, provider, durationMs, error, null, null);
    }

    public AnalyzeNodeChildrenResult withFollowUpContext(String operationId, String providerId) {
        return new AnalyzeNodeChildrenResult(scores, reasons, prompt, rawResponse, provider, durationMs, error,
                operationId, providerId, analysisReasonLength, analysisLanguage);
    }

    public static AnalyzeNodeChildrenResult empty() {
        return new AnalyzeNodeChildrenResult(
                Map.of(), Map.of(), "", "", "", 0L, null);
    }
}
