package com.taxonomy.dto;

import java.util.List;
import java.util.Objects;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Immutable analysis evidence, persisted by the existing analysis JSON snapshot boundary. */
public record RelationSearchReport(int schemaVersion, String originalSha256, String policy,
                                   List<SourceAssessment> sources, Result result,
                                   int totalCalls, int maxCalls, long durationMillis, List<String> warnings,
                                   String stopReason) {
    public RelationSearchReport {
        Objects.requireNonNull(originalSha256); Objects.requireNonNull(policy); Objects.requireNonNull(result);
        sources = List.copyOf(sources); warnings = List.copyOf(warnings);
        stopReason = stopReason == null ? "" : stopReason;
    }
    /** Exhausted only under the declared pruning policy, not proof of architectural completeness. */
    public boolean isSearchExhausted() {
        return warnings.isEmpty() && stopReason.isEmpty() && result.searchExhausted();
    }
}
