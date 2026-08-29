package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Walks the taxonomy hierarchy from root to leaf and distributes scores
 * so that every node on the path carries a score.
 *
 * <p>The distributor uses two pluggable components:
 * <ul>
 *   <li>{@link NodeScorer} — provides raw relevance scores for a batch of
 *       sibling nodes. Implementations can source scores from cloud LLMs,
 *       local embedding models, pre-recorded analysis files, or deterministic
 *       algorithms.</li>
 *   <li>{@link DistributionStrategy} — decides how those raw scores relate
 *       to the parent's score. {@link BudgetDistribution} normalises children
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
 *       — full-featured overload with one scorer and strategy.</li>
 *   <li>The mixed-strategy overload separates ordinary hierarchical nodes
 *       from independently scored terminal leaves without multiplying the
 *       parent budget.</li>
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
     * then adjusts (for example, normalise to the parent budget or keep
     * independent values).
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

        // Pre-load the full parent→children map once to avoid N+1 queries
        Map<String, List<TaxonomyNode>> childrenMap = taxonomyService.getChildrenMap();

        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();

        for (TaxonomyNode root : roots) {
            int rootScore = rootScores.getOrDefault(root.getCode(), 0);
            scores.put(root.getCode(), rootScore);
            reasons.put(root.getCode(), rootReasons.getOrDefault(root.getCode(), ""));
            walkRecursively(root.getCode(), rootScore, requirementText,
                    scorer, strategy, childrenMap, scores, reasons, rootReasons);
        }

        return new DistributionResult(scores, reasons);
    }

    /**
     * Distributes scores with separate contracts for hierarchical nodes and
     * independently scored terminal leaves.
     *
     * <p>Hierarchical siblings consume exactly one parent budget through the
     * supplied hierarchical strategy. Leaves selected by
     * {@code independentLeafPredicate} are removed from that budget and scored
     * separately. A zero-scored parent still short-circuits its complete
     * subtree, matching the runtime traversal contract. Selected independent
     * nodes must be leaves; a non-leaf selection fails closed.
     *
     * @param rootScores               two-letter root code → score
     * @param rootReasons              two-letter root code → reason
     * @param requirementText          requirement passed to both scorers
     * @param hierarchicalScorer       scorer for ordinary taxonomy children
     * @param hierarchicalStrategy     strategy for ordinary taxonomy children
     * @param independentLeafPredicate identifies terminal independent leaves
     * @param independentLeafScorer    scorer for independent leaves
     * @param independentLeafStrategy  strategy for independent leaves
     * @return scores and reasons for all nodes in the taxonomy
     */
    public DistributionResult distribute(
            Map<String, Integer> rootScores,
            Map<String, String> rootReasons,
            String requirementText,
            NodeScorer hierarchicalScorer,
            DistributionStrategy hierarchicalStrategy,
            Predicate<TaxonomyNode> independentLeafPredicate,
            NodeScorer independentLeafScorer,
            DistributionStrategy independentLeafStrategy) {

        Objects.requireNonNull(rootScores, "rootScores");
        Objects.requireNonNull(rootReasons, "rootReasons");
        Objects.requireNonNull(requirementText, "requirementText");
        Objects.requireNonNull(hierarchicalScorer, "hierarchicalScorer");
        Objects.requireNonNull(hierarchicalStrategy, "hierarchicalStrategy");
        Objects.requireNonNull(independentLeafPredicate, "independentLeafPredicate");
        Objects.requireNonNull(independentLeafScorer, "independentLeafScorer");
        Objects.requireNonNull(independentLeafStrategy, "independentLeafStrategy");

        List<TaxonomyNode> roots = taxonomyService.getRootNodes()
                .stream()
                .sorted(Comparator.comparing(TaxonomyNode::getCode))
                .toList();
        Map<String, List<TaxonomyNode>> childrenMap = taxonomyService.getChildrenMap();
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();

        for (TaxonomyNode root : roots) {
            int rootScore = rootScores.getOrDefault(root.getCode(), 0);
            scores.put(root.getCode(), rootScore);
            reasons.put(root.getCode(), rootReasons.getOrDefault(root.getCode(), ""));
            walkRecursivelyMixed(
                    root.getCode(),
                    rootScore,
                    requirementText,
                    hierarchicalScorer,
                    hierarchicalStrategy,
                    independentLeafPredicate,
                    independentLeafScorer,
                    independentLeafStrategy,
                    childrenMap,
                    scores,
                    reasons,
                    rootReasons);
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
                                 Map<String, List<TaxonomyNode>> childrenMap,
                                 Map<String, Integer> scores,
                                 Map<String, String> reasons,
                                 Map<String, String> rootReasons) {

        List<TaxonomyNode> children = childrenMap.getOrDefault(parentCode, List.of());
        if (children.isEmpty()) {
            return;
        }

        if (parentScore == 0) {
            for (TaxonomyNode child : children) {
                scores.put(child.getCode(), 0);
                String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
                reasons.put(child.getCode(), rootReasons.getOrDefault(root, ""));
                walkRecursively(child.getCode(), 0, requirementText,
                        scorer, strategy, childrenMap, scores, reasons, rootReasons);
            }
            return;
        }

        Map<String, Integer> rawScores = scorer.score(requirementText, children, parentScore);
        Map<String, Integer> adjustedScores = strategy.adjust(rawScores, parentScore);

        for (TaxonomyNode child : children) {
            if (!adjustedScores.containsKey(child.getCode())) {
                throw new IllegalStateException(
                        "DistributionStrategy '" + strategy.name()
                                + "' did not return a score for child code '"
                                + child.getCode() + "' of parent '" + parentCode + "'");
            }
        }

        for (TaxonomyNode child : children) {
            int childScore = adjustedScores.getOrDefault(child.getCode(), 0);
            scores.put(child.getCode(), childScore);
            String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
            reasons.put(child.getCode(), rootReasons.getOrDefault(root, ""));
            walkRecursively(child.getCode(), childScore, requirementText,
                    scorer, strategy, childrenMap, scores, reasons, rootReasons);
        }
    }

    private void walkRecursivelyMixed(
            String parentCode,
            int parentScore,
            String requirementText,
            NodeScorer hierarchicalScorer,
            DistributionStrategy hierarchicalStrategy,
            Predicate<TaxonomyNode> independentLeafPredicate,
            NodeScorer independentLeafScorer,
            DistributionStrategy independentLeafStrategy,
            Map<String, List<TaxonomyNode>> childrenMap,
            Map<String, Integer> scores,
            Map<String, String> reasons,
            Map<String, String> rootReasons) {

        List<TaxonomyNode> children = childrenMap.getOrDefault(parentCode, List.of())
                .stream()
                .sorted(Comparator.comparing(TaxonomyNode::getCode))
                .toList();
        if (children.isEmpty()) {
            return;
        }

        List<TaxonomyNode> hierarchicalChildren = new ArrayList<>();
        List<TaxonomyNode> independentLeaves = new ArrayList<>();
        Set<String> independentLeafCodes = new LinkedHashSet<>();
        for (TaxonomyNode child : children) {
            if (independentLeafPredicate.test(child)) {
                if (!childrenMap.getOrDefault(child.getCode(), List.of()).isEmpty()) {
                    throw new IllegalStateException(
                            "Independently scored node '" + child.getCode()
                                    + "' below '" + parentCode + "' is not a leaf");
                }
                independentLeaves.add(child);
                independentLeafCodes.add(child.getCode());
            } else {
                hierarchicalChildren.add(child);
            }
        }

        if (parentScore == 0) {
            for (TaxonomyNode child : children) {
                scores.put(child.getCode(), 0);
                String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
                reasons.put(child.getCode(), rootReasons.getOrDefault(root, ""));
                if (!independentLeafCodes.contains(child.getCode())) {
                    walkRecursivelyMixed(
                            child.getCode(),
                            0,
                            requirementText,
                            hierarchicalScorer,
                            hierarchicalStrategy,
                            independentLeafPredicate,
                            independentLeafScorer,
                            independentLeafStrategy,
                            childrenMap,
                            scores,
                            reasons,
                            rootReasons);
                }
            }
            return;
        }

        Map<String, Integer> adjustedScores = new LinkedHashMap<>();
        if (!hierarchicalChildren.isEmpty()) {
            adjustedScores.putAll(scoreAndAdjust(
                    parentCode,
                    "hierarchical",
                    requirementText,
                    hierarchicalChildren,
                    parentScore,
                    hierarchicalScorer,
                    hierarchicalStrategy));
        }
        if (!independentLeaves.isEmpty()) {
            adjustedScores.putAll(scoreAndAdjust(
                    parentCode,
                    "independent leaf",
                    requirementText,
                    independentLeaves,
                    parentScore,
                    independentLeafScorer,
                    independentLeafStrategy));
        }

        for (TaxonomyNode child : children) {
            int childScore = adjustedScores.get(child.getCode());
            scores.put(child.getCode(), childScore);
            String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
            reasons.put(child.getCode(), rootReasons.getOrDefault(root, ""));
            if (!independentLeafCodes.contains(child.getCode())) {
                walkRecursivelyMixed(
                        child.getCode(),
                        childScore,
                        requirementText,
                        hierarchicalScorer,
                        hierarchicalStrategy,
                        independentLeafPredicate,
                        independentLeafScorer,
                        independentLeafStrategy,
                        childrenMap,
                        scores,
                        reasons,
                        rootReasons);
            }
        }
    }

    private Map<String, Integer> scoreAndAdjust(
            String parentCode,
            String groupName,
            String requirementText,
            List<TaxonomyNode> nodes,
            int parentScore,
            NodeScorer scorer,
            DistributionStrategy strategy) {

        Map<String, Integer> rawScores = Objects.requireNonNull(
                scorer.score(requirementText, nodes, parentScore),
                "NodeScorer returned null for " + groupName + " children of " + parentCode);
        validateExactKeys(parentCode, groupName, "NodeScorer", nodes, rawScores);

        Map<String, Integer> adjustedScores = Objects.requireNonNull(
                strategy.adjust(rawScores, parentScore),
                "DistributionStrategy returned null for " + groupName
                        + " children of " + parentCode);
        validateExactKeys(
                parentCode,
                groupName,
                "DistributionStrategy '" + strategy.name() + "'",
                nodes,
                adjustedScores);
        return adjustedScores;
    }

    private void validateExactKeys(
            String parentCode,
            String groupName,
            String source,
            List<TaxonomyNode> nodes,
            Map<String, Integer> scoreMap) {

        Set<String> expected = new LinkedHashSet<>();
        for (TaxonomyNode node : nodes) {
            expected.add(node.getCode());
        }
        Set<String> actual = new LinkedHashSet<>(scoreMap.keySet());
        if (!actual.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalStateException(
                    source + " returned mismatched " + groupName + " keys below '"
                            + parentCode + "'; missing=" + missing
                            + ", unexpected=" + unexpected);
        }
        for (String code : expected) {
            if (scoreMap.get(code) == null) {
                throw new IllegalStateException(
                        source + " returned a null score for " + groupName
                                + " node '" + code + "' below '" + parentCode + "'");
            }
        }
    }

    // ── Path fill-in ───────────────────────────────────────────────────────

    /**
     * Fills in missing intermediate node scores so that every scored leaf
     * has a complete path of scores from its root.
     *
     * <p>For each scored non-root node, the method walks from root to that
     * node using the taxonomy parent chain. If any intermediate node is
     * missing from the map, its score is interpolated linearly between
     * the nearest scored ancestor and the nearest scored descendant on
     * the path.
     *
     * @param scores mutable map of node-code → score (modified in place)
     */
    public void fillIntermediateScores(Map<String, Integer> scores) {
        List<String> nonRootCodes = scores.keySet().stream()
                .filter(code -> code.contains("-"))
                .toList();

        for (String code : nonRootCodes) {
            List<TaxonomyNode> path = taxonomyService.getPathToRoot(code);
            if (path.size() <= 2) {
                continue;
            }

            for (int i = 1; i < path.size() - 1; i++) {
                String nodeCode = path.get(i).getCode();
                if (scores.containsKey(nodeCode)) {
                    continue;
                }

                int ancestorScore = 0;
                int ancestorIdx = 0;
                for (int a = i - 1; a >= 0; a--) {
                    Integer score = scores.get(path.get(a).getCode());
                    if (score != null) {
                        ancestorScore = score;
                        ancestorIdx = a;
                        break;
                    }
                }

                int descendantScore = 0;
                int descendantIdx = path.size() - 1;
                for (int d = i + 1; d < path.size(); d++) {
                    Integer score = scores.get(path.get(d).getCode());
                    if (score != null) {
                        descendantScore = score;
                        descendantIdx = d;
                        break;
                    }
                }

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
