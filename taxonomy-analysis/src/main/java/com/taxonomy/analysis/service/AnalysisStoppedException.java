package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cooperative stop at a call boundary, never recovery from OutOfMemoryError. */
public final class AnalysisStoppedException extends RuntimeException {
    public enum Reason { CANCELLED, MEMORY_PRESSURE, TIME_LIMIT, AWAITING_DECISION }
    private final Reason reason;
    private LlmCallDetail completedCallEvidence;
    public LlmCallDetail completedCallEvidence() { return completedCallEvidence; }
    public AnalysisStoppedException withCompletedCall(LlmCallDetail detail) {
        completedCallEvidence = detail;
        return detail.getError() == null || detail.getError().isBlank() ? withPartial(detail) : this;
    }
    private final Map<String, Integer> partialScores = new LinkedHashMap<>();
    private final Map<String, String> partialReasons = new LinkedHashMap<>();
    private final List<TaxonomyDiscrepancy> partialDiscrepancies = new ArrayList<>();

    public AnalysisStoppedException(Reason reason) {
        super(reason + ": analysis stopped before starting further work; completed results are retained.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
    public Map<String, Integer> partialScores() { return partialScores; }
    public Map<String, String> partialReasons() { return partialReasons; }
    public List<TaxonomyDiscrepancy> partialDiscrepancies() { return List.copyOf(partialDiscrepancies); }

    public AnalysisStoppedException withPartial(LlmCallDetail detail) {
        if (detail.getScores() != null) detail.getScores().forEach(partialScores::putIfAbsent);
        if (detail.getReasons() != null) detail.getReasons().forEach(partialReasons::putIfAbsent);
        if (detail.getDiscrepancy() != null && !partialDiscrepancies.contains(detail.getDiscrepancy())) {
            partialDiscrepancies.add(detail.getDiscrepancy());
        }
        return this;
    }
}
