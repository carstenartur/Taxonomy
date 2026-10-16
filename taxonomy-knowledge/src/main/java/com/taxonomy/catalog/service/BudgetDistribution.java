package com.taxonomy.catalog.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Standard hierarchical narrowing: children's scores are normalised so they
 * sum exactly to the parent's score (budget distribution).
 *
 * <p>Uses decimal-preserving arithmetic to distribute the parent budget
 * proportionally based on the raw scores.  This guarantees that:
 * <ul>
 *   <li>Every child score is a non-negative percentage, including fractions</li>
 *   <li>The sum of all child scores equals {@code parentScore} to the numeric representation precision</li>
 *   <li>Children with higher raw scores receive a proportionally larger share</li>
 * </ul>
 *
 * <p>If all raw weights are zero, the deterministic equal-weight fallback is retained.
 */
public final class BudgetDistribution implements DistributionStrategy {

    /** Singleton instance. */
    public static final BudgetDistribution INSTANCE = new BudgetDistribution();

    private BudgetDistribution() {}

    @Override
    public Map<String, Double> adjust(Map<String, Double> rawScores, double parentScore) {
        if (rawScores.isEmpty()) return Map.of();
        var weights = com.taxonomy.dto.AnalysisPercentages.copy(rawScores);
        if (weights.values().stream().allMatch(value -> value == 0)) {
            // Existing deterministic-distributor policy: equal fallback when no weight is available.
            // The LLM parser is stricter: an explicit all-zero answer remains all-zero.
            weights.replaceAll((key, value) -> 1d);
        }
        return com.taxonomy.dto.AnalysisPercentages.normalize(weights, parentScore);
    }

    @Override
    public String name() {
        return "budget";
    }

    @Override
    public String description() {
        return "Children's scores sum exactly to the parent's score (hierarchical narrowing).";
    }
}
