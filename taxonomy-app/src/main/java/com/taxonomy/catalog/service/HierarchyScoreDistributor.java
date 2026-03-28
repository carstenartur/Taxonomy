package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks the taxonomy hierarchy from root to leaf and distributes scores
 * so that every node on the path carries a score.
 *
 * <p>The distributor uses two pluggable components:
 * <ul>
 *   <li>{@link NodeScorer} — provides raw relevance scores for a batch of
 *       sibling nodes.  Implementations can source scores from cloud LLMs,
 *       local embedding models, pre-recorded analysis files, or deterministic
 *       algorithms.</li>
 *   <li>{@link DistributionStrategy} — decides how those raw scores relate
 *       to the parent's score.  {@link BudgetDistribution} normalises children
 *       to sum to the parent (standard hierarchical narrowing);
 *       {@link IndependentScoring} keeps each node's raw 0–100 score, which
 *       can discover taxonomy flaws where children match better than parents
 *       or vice versa.</li>
 * </ul>
 *
 * <h3>Convenience methods</h3>
 * <ul>
 *   <li>{@link #distribute(Map, Map)} — backward-compatible overload that
 *       uses {@link DeterministicNodeScorer} + {@link BudgetDistribution}.</li>
 *   <li>{@link #distribute(Map, Map, String, NodeScorer, DistributionStrategy)}
 *       — full-featured overload with pluggable scorer and strategy.</li>
 *   <li>{@link #fillIntermediateScores(Map)} — fills in missing intermediate
 *       nodes by linear interpolation.</li>
 * </ul>
 */
@Service
public class HierarchyScoreDistributor {

    private final TaxonomyService taxonomyService;

    public HierarchyScoreDistributor(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    // ── Result record ──────────────────────────────────────────────────────

    /**
     * Result of a full score distribution.
     *
     * @param scores  node-code → integer score for every taxonomy node
     * @param reasons node-code → human-readable reason text
     */
    public record DistributionResult(
            Map<String, Integer> scores,
            Map<String, String> reasons) {}

    // ── Full distribution (pluggable) ──────────────────────────────────────

    /**
     * Distributes root-level scores across the entire taxonomy hierarchy
     * using a pluggable scorer and strategy.
     *
     * <p>For each root, the hierarchy is walked depth-first. At every level
     * the {@code scorer} is asked for raw scores, which the {@code strategy}
     * then adjusts (e.g. normalise to parent budget, or keep independent).
     *
     * @param rootScores       two-letter root code → integer score (0–100)
     * @param rootReasons      two-letter root code → reason text
     * @param requirementText  the business requirement being analysed
     *                         (passed through to the scorer)
     * @param scorer           provides raw scores for each batch of siblings
     * @param strategy         adjusts raw scores according to its constraints
     * @return scores and reasons for <em>all</em> nodes in the taxonomy
     */
    public DistributionResult distribute(Map<String, Integer> rootScores,
                                         Map<String, String> rootReasons,
                                         String requirementText,
                                         NodeScorer scorer,
                                         DistributionStrategy strategy) {

        List<TaxonomyNode> roots = taxonomyService.getRootNodes()
                .stream()
                .sorted(Comparator.comparing(TaxonomyNode::getCode))
                .toList();

        Map<String, Integer> scores  = new LinkedHashMap<>();
        Map<String, String>  reasons = new LinkedHashMap<>();

        for (TaxonomyNode root : roots) {
            int rootScore = rootScores.getOrDefault(root.getCode(), 0);
            scores.put(root.getCode(), rootScore);
            reasons.put(root.getCode(), rootReasons.getOrDefault(root.getCode(), ""));
            walkRecursively(root.getCode(), rootScore, requirementText,
                    scorer, strategy, scores, reasons, rootReasons);
        }

        return new DistributionResult(scores, reasons);
    }

    // ── Backward-compatible overload ───────────────────────────────────────

    /**
     * Distributes root-level scores using the default
     * {@link DeterministicNodeScorer} and {@link BudgetDistribution} strategy.
     *
     * <p>This is equivalent to calling
     * {@code distribute(rootScores, rootReasons, "", DeterministicNodeScorer.INSTANCE, BudgetDistribution.INSTANCE)}.
     *
     * @param rootScores  two-letter root code → integer score (0–100)
     * @param rootReasons two-letter root code → reason text
     * @return scores and reasons for <em>all</em> nodes in the taxonomy
     */
    public DistributionResult distribute(Map<String, Integer> rootScores,
                                         Map<String, String> rootReasons) {
        return distribute(rootScores, rootReasons, "",
                DeterministicNodeScorer.INSTANCE, BudgetDistribution.INSTANCE);
    }

    // ── Recursive hierarchy walk ───────────────────────────────────────────

    private void walkRecursively(String parentCode,
                                 int parentScore,
                                 String requirementText,
                                 NodeScorer scorer,
                                 DistributionStrategy strategy,
                                 Map<String, Integer> scores,
                                 Map<String, String> reasons,
                                 Map<String, String> rootReasons) {

        List<TaxonomyNode> children = taxonomyService.getChildrenOf(parentCode);
        if (children.isEmpty()) {
            return;
        }

        // Sort by code for determinism (getChildrenOf sorts by name)
        children = children.stream()
                .sorted(Comparator.comparing(TaxonomyNode::getCode))
                .toList();

        if (parentScore == 0) {
            for (TaxonomyNode child : children) {
                scores.put(child.getCode(), 0);
                String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
                reasons.put(child.getCode(), rootReasons.getOrDefault(root, ""));
                walkRecursively(child.getCode(), 0, requirementText,
                        scorer, strategy, scores, reasons, rootReasons);
            }
            return;
        }

        // Step 1: Get raw scores from the scorer
        Map<String, Integer> rawScores = scorer.score(requirementText, children, parentScore);

        // Step 2: Apply the distribution strategy
        Map<String, Integer> adjustedScores = strategy.adjust(rawScores, parentScore);

        // Step 3: Store and recurse
        for (TaxonomyNode child : children) {
            int childScore = adjustedScores.getOrDefault(child.getCode(), 0);
            scores.put(child.getCode(), childScore);
            String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
            reasons.put(child.getCode(), rootReasons.getOrDefault(root, ""));
            walkRecursively(child.getCode(), childScore, requirementText,
                    scorer, strategy, scores, reasons, rootReasons);
        }
    }

    // ── Path fill-in ───────────────────────────────────────────────────────

    /**
     * Fills in missing intermediate node scores so that every scored leaf
     * has a complete path of scores from its root.
     *
     * <p>For each scored non-root node, the method walks from root to that
     * node using the taxonomy parent chain.  If any intermediate node is
     * missing from the map, its score is interpolated linearly between
     * the nearest scored ancestor and the nearest scored descendant on
     * the path.
     *
     * @param scores mutable map of node-code → score (modified in place)
     */
    public void fillIntermediateScores(Map<String, Integer> scores) {
        // Collect non-root scored codes; iterate a snapshot to allow mutation
        List<String> nonRootCodes = scores.keySet().stream()
                .filter(code -> code.contains("-"))
                .toList();

        for (String code : nonRootCodes) {
            List<TaxonomyNode> path = taxonomyService.getPathToRoot(code);
            if (path.size() <= 2) {
                // Direct child of root — nothing to fill
                continue;
            }

            // Walk from root towards the leaf, filling gaps
            for (int i = 1; i < path.size() - 1; i++) {
                String nodeCode = path.get(i).getCode();
                if (scores.containsKey(nodeCode)) {
                    continue; // already scored
                }

                // Find nearest scored ancestor (walking backwards from i)
                int ancestorScore = 0;
                int ancestorIdx = 0;
                for (int a = i - 1; a >= 0; a--) {
                    Integer s = scores.get(path.get(a).getCode());
                    if (s != null) {
                        ancestorScore = s;
                        ancestorIdx = a;
                        break;
                    }
                }

                // Find nearest scored descendant (walking forwards from i)
                int descendantScore = 0;
                int descendantIdx = path.size() - 1;
                for (int d = i + 1; d < path.size(); d++) {
                    Integer s = scores.get(path.get(d).getCode());
                    if (s != null) {
                        descendantScore = s;
                        descendantIdx = d;
                        break;
                    }
                }

                // Linear interpolation between ancestor and descendant
                int span = descendantIdx - ancestorIdx;
                int step = i - ancestorIdx;
                int interpolated = span > 0
                        ? ancestorScore + (descendantScore - ancestorScore) * step / span
                        : ancestorScore;

                scores.put(nodeCode, interpolated);
            }
        }
    }
}
