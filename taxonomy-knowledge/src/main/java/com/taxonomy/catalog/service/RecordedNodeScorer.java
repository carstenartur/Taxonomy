package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.SavedAnalysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores nodes by looking up pre-computed values from a {@link SavedAnalysis}.
 *
 * <p>This scorer reads scores from a previously recorded analysis (e.g. a
 * mock-score JSON file or an LLM recording export). Nodes not found in the
 * recording receive a score of zero.
 *
 * <p>When combined with {@link IndependentScoring}, this faithfully replays
 * the recorded scores. When combined with {@link BudgetDistribution}, the
 * recorded scores are used as proportional weights and redistributed so that
 * children sum to the parent — useful for regenerating normalised mock data
 * from an existing analysis.
 */
public final class RecordedNodeScorer implements NodeScorer {

    private final Map<String, Double> recordedScores;

    /**
     * Creates a scorer backed by the given recorded analysis.
     *
     * @param analysis saved analysis with pre-computed scores
     */
    public RecordedNodeScorer(SavedAnalysis analysis) {
        this.recordedScores = analysis.getScores() != null
                ? Map.copyOf(analysis.getScores())
                : Map.of();
    }

    /**
     * Creates a scorer backed by an explicit scores map (defensive copy).
     *
     * @param scores pre-computed node code → score map
     */
    public RecordedNodeScorer(Map<String, Double> scores) {
        this.recordedScores = scores != null ? Map.copyOf(scores) : Map.of();
    }

    @Override
    public Map<String, Double> score(String requirementText,
                                      List<TaxonomyNode> nodes,
                                      double parentScore) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes) {
            result.put(node.getCode(), recordedScores.getOrDefault(node.getCode(), 0d));
        }
        return result;
    }
}
