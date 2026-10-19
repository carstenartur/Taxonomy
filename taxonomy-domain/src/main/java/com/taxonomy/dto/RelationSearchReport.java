package com.taxonomy.dto;

import java.util.List;
import java.util.Objects;
import static com.taxonomy.dto.RelationSearchModel.*;

/** Immutable analysis evidence, persisted by the existing analysis JSON snapshot boundary. */
public record RelationSearchReport(int schemaVersion, String originalSha256, String policy,
                                   List<SourceAssessment> sources, Result result,
                                   int totalCalls, int maxCalls, long durationMillis, List<String> warnings,
                                   String stopReason, RelationSearchProgress progress,
                                   List<RelationSearchProgress.Task> tasks, RelationSearchBudget budget) {
    public RelationSearchReport(int schemaVersion, String originalSha256, String policy,
            List<SourceAssessment> sources, Result result, int totalCalls, int maxCalls,
            long durationMillis, List<String> warnings, String stopReason, RelationSearchProgress progress,
            List<RelationSearchProgress.Task> tasks) {
        this(schemaVersion, originalSha256, policy, sources, result, totalCalls, maxCalls,
                durationMillis, warnings, stopReason, progress, tasks, null);
    }
    public RelationSearchReport(int schemaVersion, String originalSha256, String policy,
            List<SourceAssessment> sources, Result result, int totalCalls, int maxCalls,
            long durationMillis, List<String> warnings, String stopReason) {
        this(schemaVersion, originalSha256, policy, sources, result, totalCalls, maxCalls,
                durationMillis, warnings, stopReason, null, List.of());
    }
    public RelationSearchReport {
        Objects.requireNonNull(originalSha256); Objects.requireNonNull(policy); Objects.requireNonNull(result);
        sources = List.copyOf(sources); warnings = List.copyOf(warnings);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        stopReason = stopReason == null ? "" : stopReason;
    }
    public RelationSearchReport withBudget(RelationSearchBudget usage) {
        return new RelationSearchReport(schemaVersion, originalSha256, policy, sources, result, totalCalls,
                maxCalls, durationMillis, warnings, stopReason, progress == null ? null : progress.withBudget(usage), tasks, usage);
    }
    /** Exhausted only under the declared pruning policy, not proof of architectural completeness. */
    public boolean isSearchExhausted() {
        return warnings.isEmpty() && stopReason.isEmpty() && result.searchExhausted()
                && (progress == null || progress.completedSearches() == progress.totalSearches());
    }
}
