package com.taxonomy.architecture.service;

import com.taxonomy.dto.NodeOrigin;
import com.taxonomy.dto.RequirementElementView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Selects the most semantically valuable and concrete nodes for the final
 * architecture impact presentation.
 *
 * <p>Uses a composite scoring formula that prioritises leaf-level nodes with
 * high LLM scores, good specificity (depth in the taxonomy), and
 * cross-category relation coverage. Generic near-root nodes are suppressed
 * when more concrete descendants are available.
 *
 * <h3>Composite Impact Score</h3>
 * <pre>
 * impactScore = (llmScore × 0.3)
 *             + (specificityBonus × 0.25)
 *             + (crossCategoryBonus × 0.2)
 *             + (leafConcreteness × 0.15)
 *             + (humanReadability × 0.1)
 * </pre>
 */
@Service
public class ArchitectureImpactSelector {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureImpactSelector.class);

    /** Maximum number of impact-selected nodes per taxonomy category. */
    static final int MAX_IMPACT_PER_CATEGORY = 5;

    /** Names considered generic and weak when found at level ≤ 1. */
    private static final Set<String> GENERIC_NAMES = Set.of(
            "enable", "capabilities", "support", "general", "other",
            "manage", "provide", "basic", "core", "common"
    );

    // ── Composite scoring weights ───────────────────────────────────────────

    private static final double W_LLM_SCORE        = 0.30;
    private static final double W_SPECIFICITY       = 0.25;
    private static final double W_CROSS_CATEGORY    = 0.20;
    private static final double W_LEAF_CONCRETENESS = 0.15;
    private static final double W_READABILITY       = 0.10;

    /**
     * Selects the most impactful nodes from the full element list and marks
     * them with {@link NodeOrigin#IMPACT_SELECTED}.
     *
     * @param elements           all elements from the architecture view
     * @param allScores          full LLM score map (nodeCode → 0–100)
     * @param crossCategoryCodes set of node codes that participate in cross-category relations
     * @return the input list with {@code selectedForImpact} and {@code origin} updated in place
     */
    public List<RequirementElementView> selectForImpact(List<RequirementElementView> elements,
                                                         Map<String, Integer> allScores,
                                                         Set<String> crossCategoryCodes) {
        if (elements == null || elements.isEmpty()) {
            return elements;
        }

        // Group by taxonomy category (sheet)
        Map<String, List<RequirementElementView>> byCategory = elements.stream()
                .filter(e -> e.getTaxonomySheet() != null)
                .collect(Collectors.groupingBy(RequirementElementView::getTaxonomySheet,
                                               LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<RequirementElementView>> entry : byCategory.entrySet()) {
            String category = entry.getKey();
            List<RequirementElementView> categoryElements = entry.getValue();

            // Score each element
            List<ScoredElement> scored = new ArrayList<>();
            for (RequirementElementView el : categoryElements) {
                double impactScore = computeImpactScore(el, allScores, crossCategoryCodes, categoryElements);
                scored.add(new ScoredElement(el, impactScore));
            }

            // Sort by impact score descending
            scored.sort(Comparator.comparingDouble(ScoredElement::score).reversed());

            // Select top-N, skipping generic weak nodes that have better descendants
            int selected = 0;
            for (ScoredElement se : scored) {
                if (selected >= MAX_IMPACT_PER_CATEGORY) break;

                RequirementElementView el = se.element();
                if (isTaxonomyScaffolding(el, categoryElements)
                        || isGenericWeakNode(el, allScores, categoryElements)
                        || isRedundantIntermediate(el, allScores, categoryElements)) {
                    continue;
                }

                el.setSelectedForImpact(true);
                if (el.getOrigin() == null || el.getOrigin() == NodeOrigin.PROPAGATED
                        || el.getOrigin() == NodeOrigin.TRACE_INTERMEDIATE) {
                    el.setOrigin(NodeOrigin.IMPACT_SELECTED);
                }
                el.setSpecificityScore(se.score());
                el.setPresenceReason(buildPresenceReason(el, se.score()));
                selected++;
            }

            log.debug("Impact selection for category {}: {} of {} selected",
                    category, selected, categoryElements.size());
        }

        return elements;
    }

    /**
     * Computes the composite impact score for a single element.
     */
    double computeImpactScore(RequirementElementView element,
                              Map<String, Integer> allScores,
                              Set<String> crossCategoryCodes,
                              List<RequirementElementView> categoryElements) {
        String code = element.getNodeCode();

        // 1. LLM score component (normalised to 0–1)
        double llmComponent = allScores.getOrDefault(code, 0) / 100.0;

        // 2. Specificity: deeper nodes score higher (normalised via depth / 5.0, capped at 1)
        double specificityComponent = Math.min(element.getTaxonomyDepth() / 5.0, 1.0);

        // 3. Cross-category bonus: node participates in cross-category relations
        double crossCategoryComponent = crossCategoryCodes.contains(code) ? 1.0 : 0.0;

        // 4. Leaf concreteness: has a dash (leaf code) vs. root code
        double leafComponent = code.contains("-") ? 1.0 : 0.2;

        // 5. Human readability: longer, non-generic names are more readable
        double readabilityComponent = computeReadability(element.getTitle());

        return (llmComponent * W_LLM_SCORE)
             + (specificityComponent * W_SPECIFICITY)
             + (crossCategoryComponent * W_CROSS_CATEGORY)
             + (leafComponent * W_LEAF_CONCRETENESS)
             + (readabilityComponent * W_READABILITY);
    }

    /**
     * Returns {@code true} if the node is at level ≤ 1 with a generic name
     * and a more concrete descendant with ≥ 50% of its score exists.
     */
    boolean isGenericWeakNode(RequirementElementView node,
                              Map<String, Integer> allScores,
                              List<RequirementElementView> categoryElements) {
        if (node.getTaxonomyDepth() > 1) return false;

        String name = node.getTitle();
        if (name == null || !GENERIC_NAMES.contains(name.toLowerCase().trim())) {
            return false;
        }

        int nodeScore = allScores.getOrDefault(node.getNodeCode(), 0);
        int halfScore = nodeScore / 2;

        // Check if any deeper node in the same category has a score ≥ half of this node's score
        return categoryElements.stream()
                .filter(e -> e.getTaxonomyDepth() > node.getTaxonomyDepth())
                .anyMatch(e -> allScores.getOrDefault(e.getNodeCode(), 0) >= halfScore);
    }

    /**
     * Returns {@code true} if the node is a taxonomy scaffolding node (depth&nbsp;≤&nbsp;1)
     * and more concrete descendants (depth&nbsp;&gt;&nbsp;1) exist in the same category.
     *
     * <p>Scaffolding nodes are the virtual root (e.g.&nbsp;"CP", depth&nbsp;0) and the
     * first-level container (e.g.&nbsp;"CP-1000", depth&nbsp;1). They are structural
     * grouping artifacts that do not carry distinct architectural meaning and should
     * be suppressed from the impact presentation when deeper, more specific nodes
     * are available in the same taxonomy category.
     */
    boolean isTaxonomyScaffolding(RequirementElementView node,
                                  List<RequirementElementView> categoryElements) {
        if (node.getTaxonomyDepth() > 1) return false;

        return categoryElements.stream()
                .anyMatch(e -> e.getTaxonomyDepth() > 1);
    }

    /**
     * Returns {@code true} if the node is an intermediate node that has exactly
     * one strong child (≥&nbsp;50&nbsp;% of the parent's score) in the same category.
     * Such intermediates add no information beyond the child and should be suppressed
     * so the child can represent the area directly.
     */
    boolean isRedundantIntermediate(RequirementElementView node,
                                    Map<String, Integer> allScores,
                                    List<RequirementElementView> categoryElements) {
        if (node.getTaxonomyDepth() <= 1) return false; // handled by scaffolding check

        String nodeCode = node.getNodeCode();
        int nodeScore = allScores.getOrDefault(nodeCode, 0);
        int halfScore = Math.max(nodeScore / 2, 1);

        // Count children (deeper nodes whose hierarchyPath passes through this node)
        List<RequirementElementView> strongChildren = categoryElements.stream()
                .filter(e -> e.getTaxonomyDepth() > node.getTaxonomyDepth())
                .filter(e -> isChildOf(e, nodeCode))
                .filter(e -> allScores.getOrDefault(e.getNodeCode(), 0) >= halfScore)
                .toList();

        // Suppress when exactly one strong child exists — the child alone represents the area
        return strongChildren.size() == 1;
    }

    /**
     * Returns {@code true} if {@code candidate} appears to be a descendant of
     * the node identified by {@code parentCode}, based on its hierarchy path.
     */
    private static boolean isChildOf(RequirementElementView candidate, String parentCode) {
        String path = candidate.getHierarchyPath();
        if (path != null && path.contains(parentCode)) return true;
        // Fallback: code-prefix heuristic (e.g. "BP-1490" is a child of "BP-1327"
        // when both share the same two-letter prefix) — not sufficient alone, but
        // combined with the depth check it avoids false positives across categories.
        return false;
    }

    /**
     * Builds a short human-readable reason why this node was selected for impact.
     */
    private static String buildPresenceReason(RequirementElementView el, double impactScore) {
        StringBuilder sb = new StringBuilder();
        sb.append(el.getNodeCode());
        if (el.getTitle() != null) {
            sb.append(" (").append(el.getTitle()).append(")");
        }
        sb.append(": ");
        if (el.getOrigin() != null) {
            sb.append(el.getOrigin().name().toLowerCase().replace('_', ' '));
        }
        sb.append(", score ").append(el.getDirectLlmScore());
        sb.append(", impact ").append(String.format("%.2f", impactScore));
        if (el.getTaxonomyDepth() > 0) {
            sb.append(", depth ").append(el.getTaxonomyDepth());
        }
        return sb.toString();
    }

    private static double computeReadability(String title) {
        if (title == null || title.isBlank()) return 0.0;
        String lower = title.toLowerCase().trim();
        if (GENERIC_NAMES.contains(lower)) return 0.2;
        // Longer titles tend to be more descriptive
        return Math.min(title.length() / 40.0, 1.0);
    }

    private record ScoredElement(RequirementElementView element, double score) {}
}
