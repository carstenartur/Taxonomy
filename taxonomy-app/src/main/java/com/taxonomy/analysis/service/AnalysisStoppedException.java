package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cooperative stop at a call boundary, never recovery from OutOfMemoryError. */
public final class AnalysisStoppedException extends RuntimeException {
    public enum Reason { CANCELLED, MEMORY_PRESSURE, TIME_LIMIT }
    private final Reason reason;
    private final Map<String, Integer> partialScores = new LinkedHashMap<>();
    private final Map<String, String> partialReasons = new LinkedHashMap<>();

    public AnalysisStoppedException(Reason reason) {
        super(reason + ": analysis stopped before starting further work; completed results are retained.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
    public Map<String, Integer> partialScores() { return partialScores; }
    public Map<String, String> partialReasons() { return partialReasons; }

    public AnalysisStoppedException withPartial(LlmCallDetail detail) {
        if (detail.getScores() != null) detail.getScores().forEach(partialScores::putIfAbsent);
        if (detail.getReasons() != null) detail.getReasons().forEach(partialReasons::putIfAbsent);
        return this;
    }
}
