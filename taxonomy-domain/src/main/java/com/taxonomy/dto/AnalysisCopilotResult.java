package com.taxonomy.dto;

import java.util.List;

/**
 * Server-owned reports derived from the retained evidence of one analysis.
 *
 * <p>Schema 1 states are {@code COMPLETED}, {@code PARTIAL},
 * {@code SKIPPED_PARTIAL}, and {@code SKIPPED_SCOPE}. A completed report does
 * not assert architectural completeness. A null envelope on an analysis is
 * historical/unavailable evidence and must not trigger a new analysis.
 */
public record AnalysisCopilotResult(
        int schemaVersion,
        String state,
        GapAnalysisView gaps,
        PatternDetectionView patterns,
        ArchitectureRecommendation recommendation,
        List<String> notes) {

    public AnalysisCopilotResult {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
