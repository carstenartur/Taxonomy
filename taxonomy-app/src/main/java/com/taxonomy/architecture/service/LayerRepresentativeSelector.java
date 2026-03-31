package com.taxonomy.architecture.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.RequirementElementView;

import java.util.*;

/**
 * Selects the best node representative for a taxonomy layer during leaf enrichment.
 *
 * <p>The selection strategy follows a priority order:
 * <ol>
 *   <li>Prefer leaves with depth &gt; 1 (concrete elements).</li>
 *   <li>If no concrete leaves are available and the root code scores highly enough,
 *       fall back to the best Level-1 intermediate node as a representative.</li>
 *   <li>If no intermediates exist either, the layer remains without enrichment.</li>
 * </ol>
 *
 * <p>This prevents root-code-only layers (e.g. CI, BR) from becoming isolated
 * islands in the diagram: the intermediate node has a dash in its code, so
 * {@code generateImpactRelations()} can create cross-category edges for it.
 */
class LayerRepresentativeSelector {

    private final TaxonomyNodeRepository nodeRepository;

    LayerRepresentativeSelector(TaxonomyNodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    /**
     * Decides whether a candidate node should be accepted for leaf enrichment.
     *
     * @param node     the resolved taxonomy node
     * @param rootScore the LLM score for the root code of this layer (0–100)
     * @param hasDeepLeafInLayer {@code true} if at least one depth &gt; 1 candidate
     *                           exists in the same root layer
     * @return {@code true} if the candidate should be included
     */
    boolean shouldInclude(TaxonomyNode node, int rootScore, boolean hasDeepLeafInLayer) {
        // Always accept deep leaves (depth > 1)
        if (node.getLevel() > 1) {
            return true;
        }

        // Accept level-1 intermediates as fallback when no deep leaves exist
        // and the root layer has a meaningful score
        if (node.getLevel() == 1 && !hasDeepLeafInLayer
                && rootScore >= RequirementArchitectureViewService.ANCHOR_THRESHOLD_LOW) {
            return true;
        }

        // Skip scaffolding by default
        return false;
    }
}
