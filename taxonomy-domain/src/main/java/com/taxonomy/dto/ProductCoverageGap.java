package com.taxonomy.dto;

import java.util.List;
import java.util.Map;

/**
 * Signals that a relevant product family was evaluated but none of its catalogued products
 * reached the configured suitability threshold.
 */
public record ProductCoverageGap(
        String productFamilyCode,
        String productFamilyName,
        double familyScore,
        List<String> candidateCodes,
        String reason,
        Integer minimumSuitabilityScore,
        Map<String, Double> assessedProductScores) {

    /** Earlier snapshots only recorded the post-threshold scores and a human-readable reason. */
    public ProductCoverageGap(String family, String name, double score, List<String> candidates, String reason) {
        this(family, name, score, candidates, reason, null, Map.of());
    }

    public ProductCoverageGap {
        if (minimumSuitabilityScore != null && (minimumSuitabilityScore < 0 || minimumSuitabilityScore > 100)) {
            throw new IllegalArgumentException("Invalid product suitability threshold");
        }
        candidateCodes = candidateCodes == null ? List.of() : List.copyOf(candidateCodes);
        assessedProductScores = assessedProductScores == null ? Map.of() : Map.copyOf(assessedProductScores);
        if (!candidateCodes.containsAll(assessedProductScores.keySet())) {
            throw new IllegalArgumentException("Assessed scores must belong to the recorded product candidates");
        }
        for (double value : assessedProductScores.values()) {
            if (minimumSuitabilityScore == null || AnalysisPercentages.require(value) >= minimumSuitabilityScore) {
                throw new IllegalArgumentException("Recorded product qualifies at the stated threshold");
            }
        }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public Integer minimumSuitabilityScore() { return minimumSuitabilityScore; }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    public Map<String, Double> assessedProductScores() { return assessedProductScores; }
}
