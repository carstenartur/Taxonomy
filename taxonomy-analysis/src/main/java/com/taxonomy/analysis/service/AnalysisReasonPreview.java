package com.taxonomy.analysis.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded live evidence. Explanations are retained whole; the final result remains authoritative. */
public final class AnalysisReasonPreview {
    private static final int MAX_BYTES = 1024 * 1024;
    private final Map<String, String> reasons = new LinkedHashMap<>();
    private int bytes;
    private boolean truncated;

    public void retain(String code, String reason) {
        if (reason == null || reason.isBlank()) return;
        String previous = reasons.get(code);
        if (reason.equals(previous)) return;
        int previousBytes = previous == null ? 0 : previous.getBytes(StandardCharsets.UTF_8).length;
        int reasonBytes = reason.getBytes(StandardCharsets.UTF_8).length;
        if (reasonBytes > MAX_BYTES - bytes + previousBytes) {
            truncated = true;
            return;
        }
        reasons.put(code, reason);
        bytes += reasonBytes - previousBytes;
    }

    public Map<String, String> reasons() { return Map.copyOf(reasons); }
    public boolean truncated() { return truncated; }

    /** A progress response includes explanations only for the scores it actually exposes. */
    public static AnalysisReasonPreview from(Map<String, Double> scores, Map<String, String> reasons) {
        var preview = new AnalysisReasonPreview();
        if (reasons != null) scores.keySet().forEach(code -> preview.retain(code, reasons.get(code)));
        return preview;
    }
}
