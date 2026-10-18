package com.taxonomy.portfolio.dto;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Internal Copilot selection data; never substitutes for a complete exported snapshot. */
public record SnapshotSelection(
        String id, AnalysisStatus status, long durationMs, Map<String, Double> scores,
        int warningCount, long elementMappingCount, long relationMappingCount) {
    public SnapshotSelection {
        scores = scores == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(scores));
    }
}
