package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores nodes using deterministic weights derived from their code hashes.
 *
 * <p>This scorer ignores the requirement text and produces stable,
 * reproducible scores that vary across sibling nodes but are consistent
 * across runs. Each node's weight is {@code (Math.abs(code.hashCode()) % 100) + 1}.
 *
 * <p>Intended for offline mock-score generation (see {@code MockScoreGeneratorIT}),
 * not for live analysis.  When combined with {@link BudgetDistribution}, the
 * resulting scores guarantee that children sum exactly to the parent.
 */
public final class DeterministicNodeScorer implements NodeScorer {

    /** Singleton instance. */
    public static final DeterministicNodeScorer INSTANCE = new DeterministicNodeScorer();

    private DeterministicNodeScorer() {}

    @Override
    public Map<String, Integer> score(String requirementText,
                                      List<TaxonomyNode> nodes,
                                      int parentScore) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes) {
            // Weight range: 1–100, deterministic per code
            int weight = (Math.abs(node.getCode().hashCode()) % 100) + 1;
            scores.put(node.getCode(), weight);
        }
        return scores;
    }
}
