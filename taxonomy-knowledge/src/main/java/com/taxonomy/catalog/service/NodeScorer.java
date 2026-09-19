package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;

import java.util.List;
import java.util.Map;

/**
 * Provides raw relevance scores for a batch of sibling taxonomy nodes.
 *
 * <p>Implementations can source scores from cloud LLMs, local embedding models,
 * pre-recorded analysis files, or deterministic algorithms. The returned scores
 * are <em>raw</em> — the {@link DistributionStrategy} applied by
 * {@link HierarchyScoreDistributor} decides whether they must be normalised
 * to sum to the parent budget or kept as independent values.
 *
 * @see DeterministicNodeScorer
 * @see RecordedNodeScorer
 */
@FunctionalInterface
public interface NodeScorer {

    /**
     * Scores a batch of sibling nodes for their relevance to a requirement.
     *
     * @param requirementText the business requirement being analysed
     * @param nodes           sibling nodes to score (children of the same parent)
     * @param parentScore     score of the parent node (0–100); may be ignored
     *                        by scorers that operate independently
     * @return map of node code → raw score (0–100); must contain an entry for
     *         every node in {@code nodes}
     */
    Map<String, Integer> score(String requirementText,
                               List<TaxonomyNode> nodes,
                               int parentScore);
}
