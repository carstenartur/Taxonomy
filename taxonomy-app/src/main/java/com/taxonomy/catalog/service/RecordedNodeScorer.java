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

    private final Map<String, Integer> recordedScores;

    /**
     * Creates a scorer backed by the given recorded analysis.
     *
     * @param analysis saved analysis with pre-computed scores
     */
    public RecordedNodeScorer(SavedAnalysis analysis) {
        this.recordedScores = analysis.getScores() != null
                ? analysis.getScores()
                : Map.of();
    }

    /**
     * Creates a scorer backed by an explicit scores map.
     *
     * @param scores pre-computed node code → score map
     */
    public RecordedNodeScorer(Map<String, Integer> scores) {
        this.recordedScores = scores != null ? scores : Map.of();
    }

    @Override
    public Map<String, Integer> score(String requirementText,
                                      List<TaxonomyNode> nodes,
                                      int parentScore) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (TaxonomyNode node : nodes) {
            result.put(node.getCode(), recordedScores.getOrDefault(node.getCode(), 0));
        }
        return result;
    }
}
