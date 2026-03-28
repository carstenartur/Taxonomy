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
 * <p>Uses the largest-remainder method to distribute the parent budget
 * proportionally based on the raw scores.  This guarantees that:
 * <ul>
 *   <li>Every child score is a non-negative integer</li>
 *   <li>The sum of all child scores equals {@code parentScore} exactly</li>
 *   <li>Children with higher raw scores receive a proportionally larger share</li>
 * </ul>
 *
 * <p>If all raw scores are zero, children receive zero scores.
 */
public final class BudgetDistribution implements DistributionStrategy {

    /** Singleton instance. */
    public static final BudgetDistribution INSTANCE = new BudgetDistribution();

    private BudgetDistribution() {}

    @Override
    public Map<String, Integer> adjust(Map<String, Integer> rawScores, int parentScore) {
        if (rawScores.isEmpty()) {
            return Map.of();
        }

        if (parentScore == 0) {
            Map<String, Integer> zeros = new LinkedHashMap<>();
            rawScores.keySet().forEach(k -> zeros.put(k, 0));
            return zeros;
        }

        List<String> keys = List.copyOf(rawScores.keySet());
        int[] weights = keys.stream()
                .mapToInt(k -> Math.max(rawScores.getOrDefault(k, 0), 0))
                .toArray();
        int totalWeight = Arrays.stream(weights).sum();

        if (totalWeight == 0) {
            // All raw scores are 0 — distribute equally with remainder to first nodes
            Map<String, Integer> result = new LinkedHashMap<>();
            int base = parentScore / keys.size();
            int remainder = parentScore - base * keys.size();
            for (int i = 0; i < keys.size(); i++) {
                result.put(keys.get(i), base + (i < remainder ? 1 : 0));
            }
            return result;
        }

        // Largest-remainder proportional distribution
        int[] childScores = new int[keys.size()];
        double[] fractions = new double[keys.size()];
        int distributed = 0;
        for (int i = 0; i < keys.size(); i++) {
            double raw = (double) parentScore * weights[i] / totalWeight;
            childScores[i] = (int) raw;
            fractions[i] = raw - childScores[i];
            distributed += childScores[i];
        }

        int remainder = parentScore - distributed;
        Integer[] byFraction = IntStream.range(0, keys.size())
                .boxed()
                .sorted((a, b) -> Double.compare(fractions[b], fractions[a]))
                .toArray(Integer[]::new);
        for (int i = 0; i < remainder; i++) {
            childScores[byFraction[i]]++;
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            result.put(keys.get(i), childScores[i]);
        }
        return result;
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
