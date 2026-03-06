package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.*;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Builds a {@link RequirementArchitectureView} from analysis scores and
 * persisted taxonomy relations.
 */
@Service
public class RequirementArchitectureViewService {

    private static final Logger log = LoggerFactory.getLogger(RequirementArchitectureViewService.class);

    /** Minimum score to be automatically selected as an anchor. */
    static final int ANCHOR_THRESHOLD_HIGH = 70;

    /** Fallback threshold when fewer than MIN_ANCHORS have score >= ANCHOR_THRESHOLD_HIGH. */
    static final int ANCHOR_THRESHOLD_LOW = 50;

    /** Minimum number of anchors to try to select when using fallback threshold. */
    static final int MIN_ANCHORS = 3;

    private final RelevancePropagationService propagationService;
    private final TaxonomyNodeRepository nodeRepository;

    public RequirementArchitectureViewService(RelevancePropagationService propagationService,
                                              TaxonomyNodeRepository nodeRepository) {
        this.propagationService = propagationService;
        this.nodeRepository = nodeRepository;
    }

    /**
     * Builds the architecture view from analysis scores.
     *
     * @param scores       map of nodeCode → integer score (0–100) from the LLM analysis
     * @param businessText the original business requirement text (for notes)
     * @return the architecture view, or an empty view with a note if no anchors are found
     */
    @Transactional(readOnly = true)
    public RequirementArchitectureView build(Map<String, Integer> scores, String businessText) {
        RequirementArchitectureView view = new RequirementArchitectureView();

        if (scores == null || scores.isEmpty()) {
            view.getNotes().add("No scores available; architecture view cannot be built.");
            return view;
        }

        // 1. Select anchors
        List<RequirementAnchor> anchors = selectAnchors(scores);
        view.setAnchors(anchors);

        if (anchors.isEmpty()) {
            view.getNotes().add("No nodes met the anchor threshold; architecture view is empty.");
            return view;
        }

        // 2. Build anchor relevance map
        Map<String, Double> anchorRelevances = new LinkedHashMap<>();
        for (RequirementAnchor anchor : anchors) {
            anchorRelevances.put(anchor.getNodeCode(), anchor.getDirectScore() / 100.0);
        }

        // 3. Propagate relevance through relations
        PropagationResult propagation = propagationService.propagate(anchorRelevances);

        // 4. Build included elements
        List<RequirementElementView> elements = buildElements(propagation);
        view.setIncludedElements(elements);

        // 5. Build included relationships
        List<RequirementRelationshipView> relationships = buildRelationships(propagation);
        view.setIncludedRelationships(relationships);

        // 6. Add notes
        if (relationships.isEmpty() && elements.size() == anchors.size()) {
            view.getNotes().add("No traversable relations found for anchor nodes; " +
                    "only direct matches are included.");
        }

        log.info("Architecture view built: {} anchors, {} elements, {} relationships",
                anchors.size(), elements.size(), relationships.size());

        return view;
    }

    /**
     * Selects anchor nodes from the scores according to the anchor selection rules:
     * - All nodes with score >= 70
     * - If fewer than 3, top-3 with score >= 50
     */
    List<RequirementAnchor> selectAnchors(Map<String, Integer> scores) {
        List<RequirementAnchor> highAnchors = new ArrayList<>();

        // Collect all nodes >= ANCHOR_THRESHOLD_HIGH
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() >= ANCHOR_THRESHOLD_HIGH) {
                highAnchors.add(new RequirementAnchor(
                        entry.getKey(), entry.getValue(), "high direct match"));
            }
        }

        if (highAnchors.size() >= MIN_ANCHORS) {
            highAnchors.sort(Comparator.comparingInt(RequirementAnchor::getDirectScore).reversed());
            return highAnchors;
        }

        // Fallback: collect top-3 with score >= ANCHOR_THRESHOLD_LOW
        List<Map.Entry<String, Integer>> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() >= ANCHOR_THRESHOLD_LOW) {
                candidates.add(entry);
            }
        }
        candidates.sort(Comparator.<Map.Entry<String, Integer>, Integer>comparing(Map.Entry::getValue).reversed());

        List<RequirementAnchor> anchors = new ArrayList<>();
        for (int i = 0; i < Math.min(MIN_ANCHORS, candidates.size()); i++) {
            Map.Entry<String, Integer> entry = candidates.get(i);
            anchors.add(new RequirementAnchor(
                    entry.getKey(), entry.getValue(), "top candidate (fallback)"));
        }

        return anchors;
    }

    private List<RequirementElementView> buildElements(PropagationResult propagation) {
        List<RequirementElementView> elements = new ArrayList<>();

        for (Map.Entry<String, Double> entry : propagation.getRelevanceMap().entrySet()) {
            String nodeCode = entry.getKey();
            double relevance = entry.getValue();
            int hopDistance = propagation.getHopDistanceMap().getOrDefault(nodeCode, 0);
            String reason = propagation.getReasonMap().getOrDefault(nodeCode, "unknown");

            RequirementElementView element = new RequirementElementView();
            element.setNodeCode(nodeCode);
            element.setRelevance(relevance);
            element.setHopDistance(hopDistance);
            element.setAnchor(hopDistance == 0);
            element.setIncludedBecause(reason);

            // Look up title and taxonomy sheet from the database
            Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
            if (nodeOpt.isPresent()) {
                TaxonomyNode node = nodeOpt.get();
                element.setTitle(node.getNameEn());
                element.setTaxonomySheet(node.getTaxonomyRoot());
            }

            elements.add(element);
        }

        // Sort: anchors first, then by relevance descending
        elements.sort(Comparator
                .comparing(RequirementElementView::isAnchor).reversed()
                .thenComparing(Comparator.comparingDouble(RequirementElementView::getRelevance).reversed()));

        return elements;
    }

    private List<RequirementRelationshipView> buildRelationships(PropagationResult propagation) {
        List<RequirementRelationshipView> relationships = new ArrayList<>();
        Set<String> includedNodeCodes = propagation.getRelevanceMap().keySet();

        for (PropagationResult.TraversedRelation tr : propagation.getTraversedRelations()) {
            TaxonomyRelationDto rel = tr.getRelation();

            // Only include relationships where both endpoints are in the included elements
            if (!includedNodeCodes.contains(rel.getSourceCode()) ||
                    !includedNodeCodes.contains(rel.getTargetCode())) {
                continue;
            }

            RequirementRelationshipView rv = new RequirementRelationshipView();
            rv.setRelationId(rel.getId());
            rv.setSourceCode(rel.getSourceCode());
            rv.setTargetCode(rel.getTargetCode());
            rv.setRelationType(rel.getRelationType());
            rv.setPropagatedRelevance(tr.getPropagatedRelevance());
            rv.setHopDistance(tr.getHopDistance());
            rv.setIncludedBecause(tr.getReason());
            relationships.add(rv);
        }

        // Deduplicate by relationId, keeping the one with highest propagatedRelevance
        Map<Long, RequirementRelationshipView> deduped = new LinkedHashMap<>();
        for (RequirementRelationshipView rv : relationships) {
            Long key = rv.getRelationId();
            RequirementRelationshipView existing = deduped.get(key);
            if (existing == null || rv.getPropagatedRelevance() > existing.getPropagatedRelevance()) {
                deduped.put(key, rv);
            }
        }

        return new ArrayList<>(deduped.values());
    }
}
