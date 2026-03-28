package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Walks the taxonomy hierarchy from root to leaf and distributes scores
 * so that every node on the path carries a score.
 *
 * <p>The distributor operates in two complementary modes:
 *
 * <h3>1 — Full distribution ({@link #distribute})</h3>
 * Takes a set of root-level scores and recursively distributes each root's
 * budget across all its descendants.  Every child's score is proportional
 * to a deterministic weight derived from its node code, and at every level
 * the children's scores sum exactly to their parent's score.
 *
 * <p>This mode is used by {@code MockScoreGeneratorIT} to produce the
 * mock-score JSON files that ship in {@code src/main/resources/mock-scores/}.
 *
 * <h3>2 — Path fill-in ({@link #fillIntermediateScores})</h3>
 * Takes an existing partial scores map (roots + selected leaves) and
 * fills in the missing intermediate nodes so that the path from every
 * root to every scored leaf is complete.  Intermediate scores are
 * interpolated linearly between the nearest scored ancestor and the
 * nearest scored descendant on each path.
 *
 * <p>This mode is useful when hand-crafting showcase scores
 * (e.g.&nbsp;in {@code ReadmeShowcaseTest}) where only the root and
 * a few key leaf scores are known, and the intermediate values should
 * be derived automatically.
 */
@Service
public class HierarchyScoreDistributor {

    private final TaxonomyService taxonomyService;

    public HierarchyScoreDistributor(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    // ── Full distribution ──────────────────────────────────────────────────

    /**
     * Result of a full score distribution.
     *
     * @param scores  node-code → integer score for every taxonomy node
     * @param reasons node-code → human-readable reason text
     */
    public record DistributionResult(
            Map<String, Integer> scores,
            Map<String, String> reasons) {}

    /**
     * Distributes root-level scores across the entire taxonomy hierarchy.
     *
     * <p>For each root, the given score is subdivided among its children
     * at every level so that child scores always sum exactly to their
     * parent's score.  The weight assigned to each child is deterministic
     * (derived from {@code Math.abs(code.hashCode()) % 100 + 1}).
     *
     * @param rootScores  two-letter root code → integer score (0–100)
     * @param rootReasons two-letter root code → reason text
     * @return scores and reasons for <em>all</em> nodes in the taxonomy
     */
    public DistributionResult distribute(Map<String, Integer> rootScores,
                                         Map<String, String> rootReasons) {

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
            distributeRecursively(root.getCode(), rootScore, scores, reasons, rootReasons);
        }

        return new DistributionResult(scores, reasons);
    }

    /**
     * Recursively distributes {@code parentScore} across the children of
     * {@code parentCode}, guaranteeing that the sum of all child scores
     * equals {@code parentScore}.
     */
    private void distributeRecursively(String parentCode,
                                       int parentScore,
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
                distributeRecursively(child.getCode(), 0, scores, reasons, rootReasons);
            }
            return;
        }

        // Deterministic weights from node codes
        int[] weights = children.stream()
                .mapToInt(c -> (Math.abs(c.getCode().hashCode()) % 100) + 1)
                .toArray();
        int totalWeight = Arrays.stream(weights).sum();

        // Floor-based proportional distribution
        int[] childScores = new int[children.size()];
        double[] fractions = new double[children.size()];
        int distributed = 0;
        for (int i = 0; i < children.size(); i++) {
            double raw = (double) parentScore * weights[i] / totalWeight;
            childScores[i] = (int) raw;
            fractions[i]   = raw - childScores[i];
            distributed   += childScores[i];
        }

        // Distribute remainder to nodes with largest fractional parts
        int remainder = parentScore - distributed;
        Integer[] byFraction = IntStream.range(0, children.size())
                .boxed()
                .sorted((a, b) -> Double.compare(fractions[b], fractions[a]))
                .toArray(Integer[]::new);
        for (int i = 0; i < remainder; i++) {
            childScores[byFraction[i]]++;
        }

        // Store scores and recurse
        for (int i = 0; i < children.size(); i++) {
            TaxonomyNode child = children.get(i);
            scores.put(child.getCode(), childScores[i]);
            String root = child.getTaxonomyRoot() != null ? child.getTaxonomyRoot() : "";
            reasons.put(child.getCode(), rootReasons.getOrDefault(root, ""));
            distributeRecursively(child.getCode(), childScores[i], scores, reasons, rootReasons);
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
