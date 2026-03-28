package com.taxonomy.catalog.service;

import java.util.Map;

/**
 * Controls how raw scores from a {@link NodeScorer} are adjusted before
 * being stored in the hierarchy walk.
 *
 * <p>Two built-in strategies are provided:
 * <ul>
 *   <li>{@link BudgetDistribution} — children's scores are normalised so they
 *       sum exactly to the parent's score.  This is the standard hierarchical
 *       narrowing model used by LLM analysis.</li>
 *   <li>{@link IndependentScoring} — each node keeps its raw 0–100 score
 *       regardless of the parent.  Useful for discovering taxonomy flaws
 *       where child nodes match a requirement better than the parent, or
 *       vice versa.</li>
 * </ul>
 *
 * @see HierarchyScoreDistributor
 */
public interface DistributionStrategy {

    /**
     * Adjusts raw scores returned by a {@link NodeScorer} according to this
     * strategy's constraints.
     *
     * @param rawScores   raw scores from the scorer (node code → 0–100)
     * @param parentScore the parent node's score (0–100)
     * @return adjusted scores (node code → 0–100); the returned map must
     *         contain the same keys as {@code rawScores}
     */
    Map<String, Integer> adjust(Map<String, Integer> rawScores, int parentScore);

    /** Short identifier for logging/display (e.g. {@code "budget"}, {@code "independent"}). */
    String name();

    /** Human-readable description of the strategy. */
    String description();
}
