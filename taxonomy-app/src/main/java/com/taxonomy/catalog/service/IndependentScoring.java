package com.taxonomy.catalog.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Independent scoring: each node keeps its raw 0–100 score from the
 * {@link NodeScorer}, regardless of the parent's score.
 *
 * <p>This strategy is useful for discovering <strong>taxonomy flaws</strong>
 * where child nodes match a requirement better than the parent, or where the
 * parent is highly relevant but none of its children are. Such mismatches
 * indicate potential gaps or misclassifications in the taxonomy hierarchy.
 *
 * <p>Because scores are not normalised to the parent budget, a child's score
 * can exceed the parent's score — this is intentional and represents a finding.
 */
public final class IndependentScoring implements DistributionStrategy {

    /** Singleton instance. */
    public static final IndependentScoring INSTANCE = new IndependentScoring();

    private IndependentScoring() {}

    @Override
    public Map<String, Integer> adjust(Map<String, Integer> rawScores, int parentScore) {
        // Keep raw scores unchanged — clamp to [0, 100] only
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : rawScores.entrySet()) {
            result.put(entry.getKey(), Math.max(0, Math.min(100, entry.getValue())));
        }
        return result;
    }

    @Override
    public String name() {
        return "independent";
    }

    @Override
    public String description() {
        return "Each node scored independently (0–100), ignoring parent values. "
                + "Can discover taxonomy flaws where children match better than parents.";
    }
}
