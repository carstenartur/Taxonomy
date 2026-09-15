package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyDiscrepancy;

import java.util.LinkedHashMap;
import java.util.Map;

/** Incremental diagnostic aggregation; scores and explanations are never truncated. */
final class LlmDetailAccumulator {
    private static final int TEXT_LIMIT = 32_768;
    private static final String TRUNCATED = "\n[diagnostic transcript truncated]";
    private final Map<String, Integer> scores = new LinkedHashMap<>();
    private final Map<String, String> reasons = new LinkedHashMap<>();
    private final StringBuilder prompts = new StringBuilder();
    private final StringBuilder responses = new StringBuilder();
    private final StringBuilder errors = new StringBuilder();
    private String provider;
    private TaxonomyDiscrepancy discrepancy;
    private long duration;
    private int count;

    void add(LlmCallDetail detail) {
        count++;
        if (detail.getScores() != null) scores.putAll(detail.getScores());
        if (detail.getReasons() != null) reasons.putAll(detail.getReasons());
        if (provider == null) provider = detail.getProvider();
        if (discrepancy == null) discrepancy = detail.getDiscrepancy();
        duration += detail.getDurationMs();
        if (detail.getPrompt() != null) {
            append(prompts, "--- call " + count + " ---\n");
            append(prompts, detail.getPrompt());
            append(prompts, "\n");
        }
        if (detail.getRawResponse() != null) {
            append(responses, "--- call " + count + " ---\n");
            append(responses, detail.getRawResponse());
            append(responses, "\n");
        }
        if (detail.getError() != null) {
            if (!errors.isEmpty()) append(errors, "; ");
            append(errors, detail.getError());
        }
    }

    LlmCallDetail result() {
        LlmCallDetail result = new LlmCallDetail();
        result.setScores(scores);
        result.setReasons(reasons);
        result.setProvider(provider);
        result.setPrompt(prompts.toString());
        result.setRawResponse(responses.toString());
        result.setError(errors.isEmpty() ? null : errors.toString());
        result.setDurationMs(duration);
        result.setDiscrepancy(discrepancy);
        return result;
    }

    private static void append(StringBuilder target, String value) {
        if (target.length() >= TEXT_LIMIT) return;
        if (value.length() <= TEXT_LIMIT - target.length()) {
            target.append(value);
            return;
        }
        int contentLimit = TEXT_LIMIT - TRUNCATED.length();
        if (target.length() > contentLimit) target.setLength(contentLimit);
        target.append(value, 0, Math.min(value.length(), contentLimit - target.length()));
        target.append(TRUNCATED);
    }
}
