package com.taxonomy.architecture.service;

import com.taxonomy.dto.RequirementElementView;

import java.util.*;

/**
 * Selects all qualified impact endpoints from a list of leaf nodes in the same
 * taxonomy category, rather than picking only the single "best" leaf.
 *
 * <p>A leaf qualifies as an impact endpoint when its composite score
 * (relevance × 0.5 + normalised depth × 0.3 + impact-selection bonus × 0.2)
 * exceeds the qualification threshold, or when it is an anchor, or when it was
 * explicitly marked as selected-for-impact by the {@link ArchitectureImpactSelector}.
 */
class ImpactEndpointSelector {

    /** Minimum composite score for automatic qualification. */
    static final double QUALIFICATION_THRESHOLD = 0.35;

    /** Maximum depth used for normalisation. */
    private static final double MAX_DEPTH = 5.0;

    /**
     * Returns all qualified impact endpoints from the provided leaves.
     * The result is ordered by composite score descending.
     *
     * @param leaves non-empty list of leaf elements from the same taxonomy category
     * @return list of qualified endpoints (never empty — at least the best leaf is always included)
     */
    List<RequirementElementView> selectEndpoints(List<RequirementElementView> leaves) {
        if (leaves == null || leaves.isEmpty()) {
            return List.of();
        }

        record Scored(RequirementElementView element, double score) {}

        List<Scored> scored = new ArrayList<>();
        for (RequirementElementView leaf : leaves) {
            double relevanceComponent = leaf.getRelevance() * 0.5;
            double depthComponent = Math.min(pathDepth(leaf.getHierarchyPath()) / MAX_DEPTH, 1.0) * 0.3;
            double impactBonus = leaf.isSelectedForImpact() ? 0.2 : 0.0;
            double compositeScore = relevanceComponent + depthComponent + impactBonus;
            scored.add(new Scored(leaf, compositeScore));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<RequirementElementView> qualified = new ArrayList<>();
        for (Scored s : scored) {
            if (s.score() >= QUALIFICATION_THRESHOLD
                    || s.element().isAnchor()
                    || s.element().isSelectedForImpact()) {
                qualified.add(s.element());
            }
        }

        // Guarantee at least the best leaf is always included
        if (qualified.isEmpty()) {
            qualified.add(scored.get(0).element());
        }

        return qualified;
    }

    /**
     * Returns the depth of a hierarchy path by counting path segments.
     */
    private static int pathDepth(String path) {
        if (path == null || path.isEmpty()) return 0;
        int count = 1;
        int idx = 0;
        while ((idx = path.indexOf(" > ", idx)) >= 0) {
            count++;
            idx += 3;
        }
        return count;
    }
}
