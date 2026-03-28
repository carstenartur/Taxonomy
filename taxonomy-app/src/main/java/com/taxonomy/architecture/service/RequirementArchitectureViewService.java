package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import com.taxonomy.catalog.service.PropagationResult;
import com.taxonomy.catalog.service.RelevancePropagationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.RequirementElementView;
import com.taxonomy.dto.RequirementRelationshipView;
import com.taxonomy.dto.TaxonomyRelationDto;

/**
 * Builds a {@link RequirementArchitectureView} from analysis scores and
 * persisted taxonomy relations.
 *
 * <p>After propagation through root-level seed relations, the view is enriched
 * with the top-scoring leaf nodes from the original analysis. This ensures each
 * architecture layer shows concrete named elements (e.g. "CO-1023 Secure
 * Messaging Service") rather than just abstract root codes.
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

    /** Maximum number of top-scoring leaf nodes to add per taxonomy root during enrichment. */
    static final int MAX_LEAF_ENRICHMENT = 3;

    /** Minimum score for a leaf node to be included in the enrichment (absolute, 0-100). */
    static final int LEAF_ENRICHMENT_MIN_SCORE = 5;

    private final RelevancePropagationService propagationService;
    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyService taxonomyService;

    public RequirementArchitectureViewService(RelevancePropagationService propagationService,
                                              TaxonomyNodeRepository nodeRepository,
                                              TaxonomyService taxonomyService) {
        this.propagationService = propagationService;
        this.nodeRepository = nodeRepository;
        this.taxonomyService = taxonomyService;
    }

    /**
     * Builds the architecture view from analysis scores (without provisional relations).
     *
     * @param scores               map of nodeCode → integer score (0–100) from the LLM analysis
     * @param businessText         the original business requirement text (for notes)
     * @param maxArchitectureNodes maximum number of elements to include (0 = no limit)
     * @return the architecture view, or an empty view with a note if no anchors are found
     */
    @Transactional(readOnly = true)
    public RequirementArchitectureView build(Map<String, Integer> scores, String businessText,
                                             int maxArchitectureNodes) {
        return build(scores, businessText, maxArchitectureNodes, null);
    }

    /**
     * Builds the architecture view from analysis scores, optionally using
     * provisional relation hypotheses as virtual edges for relevance propagation
     * when no confirmed relations exist.
     *
     * @param scores               map of nodeCode → integer score (0–100) from the LLM analysis
     * @param businessText         the original business requirement text (for notes)
     * @param maxArchitectureNodes maximum number of elements to include (0 = no limit)
     * @param provisionalRelations optional list of AI-suggested relation hypotheses
     * @return the architecture view, or an empty view with a note if no anchors are found
     */
    @Transactional(readOnly = true)
    public RequirementArchitectureView build(Map<String, Integer> scores, String businessText,
                                             int maxArchitectureNodes,
                                             List<RelationHypothesisDto> provisionalRelations) {
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
        Map<String, String> pathCache = new HashMap<>();
        List<RequirementElementView> elements = buildElements(propagation, pathCache);

        // 4b. Enrich with top-scoring leaf nodes so each layer shows concrete elements
        enrichWithLeafNodes(elements, scores, pathCache);

        // 5. Build included relationships (trace relations from propagation)
        List<RequirementRelationshipView> relationships = buildRelationships(propagation);

        // 5b. If no confirmed relationships were found but provisional relations exist,
        //     add them as virtual edges so the architecture view is useful from day one.
        boolean usedProvisional = false;
        if (relationships.isEmpty()
                && provisionalRelations != null && !provisionalRelations.isEmpty()) {
            Set<String> includedCodes = elements.stream()
                    .map(RequirementElementView::getNodeCode)
                    .collect(Collectors.toSet());

            for (RelationHypothesisDto hyp : provisionalRelations) {
                // Ensure both endpoints are in the included elements set
                // (they may not be if one node scored below the anchor threshold)
                ensureElement(elements, includedCodes, hyp.getSourceCode(), hyp.getSourceName(), scores, pathCache);
                ensureElement(elements, includedCodes, hyp.getTargetCode(), hyp.getTargetName(), scores, pathCache);

                RequirementRelationshipView rv = new RequirementRelationshipView();
                rv.setSourceCode(hyp.getSourceCode());
                rv.setTargetCode(hyp.getTargetCode());
                rv.setRelationType(hyp.getRelationType());
                rv.setPropagatedRelevance(hyp.getConfidence());
                rv.setHopDistance(0);
                rv.setIncludedBecause("provisional (AI-suggested, not yet confirmed)");
                relationships.add(rv);
            }
            usedProvisional = true;
        }

        // 6. Truncate to maxArchitectureNodes if requested
        if (maxArchitectureNodes > 0 && elements.size() > maxArchitectureNodes) {
            Set<String> keptCodes = elements.subList(0, maxArchitectureNodes).stream()
                    .map(RequirementElementView::getNodeCode).collect(Collectors.toSet());
            elements = new ArrayList<>(elements.subList(0, maxArchitectureNodes));
            relationships = relationships.stream()
                    .filter(r -> keptCodes.contains(r.getSourceCode()) && keptCodes.contains(r.getTargetCode()))
                    .collect(Collectors.toList());
            view.getNotes().add("Architecture view limited to " + maxArchitectureNodes + " elements.");
        }

        // 7. Generate concrete impact relations AFTER truncation so that
        //    only elements surviving the limit are used as endpoints.
        generateImpactRelations(elements, relationships);

        view.setIncludedElements(elements);
        view.setIncludedRelationships(relationships);

        // 8. Emit impact relations as provisional hypotheses so they flow
        //    through the existing Accept/Reject UI and can be LLM-evaluated.
        emitImpactHypotheses(relationships, provisionalRelations);

        // 9. Add notes
        if (usedProvisional) {
            view.getNotes().add("Architecture view built using AI-suggested provisional relations " +
                    "(not yet confirmed).");
        } else if (relationships.isEmpty() && elements.size() == anchors.size()) {
            view.getNotes().add("No traversable relations found for anchor nodes; " +
                    "only direct matches are included.");
        }

        // 10. Populate summary statistics
        int maxHop = elements.stream().mapToInt(RequirementElementView::getHopDistance).max().orElse(0);
        view.setTotalAnchors(anchors.size());
        view.setTotalElements(elements.size());
        view.setTotalRelationships(relationships.size());
        view.setMaxHopDistance(maxHop);

        // 11. Structured logging
        log.info("RequirementArchitectureView summary: anchors={}, elements={}, relationships={}, maxHopDistance={}",
                anchors.size(), elements.size(), relationships.size(), maxHop);

        for (RequirementAnchor anchor : anchors) {
            List<RequirementElementView> propagated = elements.stream()
                    .filter(e -> !e.isAnchor() && e.getIncludedBecause() != null
                            && e.getIncludedBecause().contains(anchor.getNodeCode()))
                    .toList();
            if (!propagated.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("anchor ").append(anchor.getNodeCode());
                for (RequirementElementView e : propagated) {
                    sb.append("\n  → ").append(e.getNodeCode())
                      .append(" (").append(String.format(Locale.US, "%.2f", e.getRelevance())).append(")");
                }
                log.debug(sb.toString());
            }
        }

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

    private List<RequirementElementView> buildElements(PropagationResult propagation,
                                                       Map<String, String> pathCache) {
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

            element.setHierarchyPath(buildHierarchyPath(nodeCode, pathCache));

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
            rv.setRelationCategory(RequirementRelationshipView.CATEGORY_TRACE);
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

    /**
     * Ensures a node is present in the included elements set, adding it if missing.
     * Used when provisional relations reference nodes not already included by propagation.
     */
    private void ensureElement(List<RequirementElementView> elements, Set<String> includedCodes,
                               String nodeCode, String nodeName, Map<String, Integer> scores,
                               Map<String, String> pathCache) {
        if (includedCodes.contains(nodeCode)) {
            return;
        }
        RequirementElementView element = new RequirementElementView();
        element.setNodeCode(nodeCode);
        element.setRelevance(scores.getOrDefault(nodeCode, 0) / 100.0);
        element.setHopDistance(0);
        element.setAnchor(false);
        element.setIncludedBecause("provisional relation endpoint");
        element.setTitle(nodeName);

        Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
        if (nodeOpt.isPresent()) {
            TaxonomyNode node = nodeOpt.get();
            if (element.getTitle() == null) {
                element.setTitle(node.getNameEn());
            }
            element.setTaxonomySheet(node.getTaxonomyRoot());
        }

        element.setHierarchyPath(buildHierarchyPath(nodeCode, pathCache));

        elements.add(element);
        includedCodes.add(nodeCode);
    }

    /**
     * Enriches the architecture view with top-scoring leaf nodes from the analysis.
     *
     * <p>Propagation through root-level seed relations produces only root codes (CP, CO, CR, etc.).
     * This method examines the full LLM score map and adds the highest-scoring leaf nodes
     * (e.g. "CO-1063 Tactical Radio Gateway", "BP-1481 Clinical Workflow Management")
     * for every taxonomy root that already has at least one element in the view.
     * This gives each architecture layer concrete, named substance.
     */
    private void enrichWithLeafNodes(List<RequirementElementView> elements, Map<String, Integer> scores,
                                      Map<String, String> pathCache) {
        Set<String> includedCodes = elements.stream()
                .map(RequirementElementView::getNodeCode)
                .collect(Collectors.toCollection(HashSet::new));

        // Determine which taxonomy roots are already represented
        Set<String> representedRoots = elements.stream()
                .map(RequirementElementView::getTaxonomySheet)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Group leaf-level scores by their taxonomy root
        Map<String, List<Map.Entry<String, Integer>>> leafScoresByRoot = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            String code = entry.getKey();
            int score = entry.getValue();
            if (score < LEAF_ENRICHMENT_MIN_SCORE) continue;
            if (includedCodes.contains(code)) continue;
            // Must be a leaf code (contains a dash, e.g. "CO-1063")
            if (!code.contains("-")) continue;

            String rootPrefix = code.substring(0, code.indexOf('-'));
            if (!representedRoots.contains(rootPrefix)) continue;

            leafScoresByRoot.computeIfAbsent(rootPrefix, k -> new ArrayList<>()).add(entry);
        }

        // For each root, pick the top-N leaf nodes by score
        for (Map.Entry<String, List<Map.Entry<String, Integer>>> rootEntry : leafScoresByRoot.entrySet()) {
            String root = rootEntry.getKey();
            List<Map.Entry<String, Integer>> candidates = rootEntry.getValue();
            candidates.sort(Comparator.<Map.Entry<String, Integer>, Integer>comparing(Map.Entry::getValue).reversed());

            int added = 0;
            for (Map.Entry<String, Integer> candidate : candidates) {
                if (added >= MAX_LEAF_ENRICHMENT) break;
                String leafCode = candidate.getKey();
                int leafScore = candidate.getValue();

                RequirementElementView element = new RequirementElementView();
                element.setNodeCode(leafCode);
                element.setRelevance(leafScore / 100.0);
                boolean isAnchor = leafScore >= ANCHOR_THRESHOLD_HIGH;
                element.setHopDistance(isAnchor ? 0 : 1);
                element.setAnchor(isAnchor);
                element.setIncludedBecause("leaf-enrichment: top-scoring in " + root);

                Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(leafCode);
                if (nodeOpt.isPresent()) {
                    TaxonomyNode node = nodeOpt.get();
                    element.setTitle(node.getNameEn());
                    element.setTaxonomySheet(node.getTaxonomyRoot());
                } else {
                    element.setTaxonomySheet(root);
                }

                element.setHierarchyPath(buildHierarchyPath(leafCode, pathCache));

                elements.add(element);
                includedCodes.add(leafCode);
                added++;
            }

            if (added > 0) {
                log.debug("Enriched layer {} with {} leaf node(s)", root, added);
            }
        }

        // Re-sort: anchors first, then by relevance descending
        elements.sort(Comparator
                .comparing(RequirementElementView::isAnchor).reversed()
                .thenComparing(Comparator.comparingDouble(RequirementElementView::getRelevance).reversed()));
    }

    /**
     * Emits derived impact relations as {@link RelationHypothesisDto} entries
     * into the provisional relations list, so they flow through the existing
     * Accept/Reject UI and can be evaluated by the LLM.
     *
     * <p>Only impact-category relations that don't already exist as confirmed
     * or provisional relations are emitted. The confidence is derived from the
     * propagated relevance, and the reasoning indicates the derivation source.
     */
    private void emitImpactHypotheses(List<RequirementRelationshipView> relationships,
                                       List<RelationHypothesisDto> provisionalRelations) {
        if (provisionalRelations == null) return;

        Set<String> existingSignatures = new LinkedHashSet<>();
        for (RelationHypothesisDto h : provisionalRelations) {
            existingSignatures.add(h.getSourceCode() + "->" + h.getTargetCode() + ":" + h.getRelationType());
        }

        for (RequirementRelationshipView rel : relationships) {
            if (!RequirementRelationshipView.CATEGORY_IMPACT.equals(rel.getRelationCategory())) {
                continue;
            }
            String sig = rel.getSourceCode() + "->" + rel.getTargetCode() + ":" + rel.getRelationType();
            if (!existingSignatures.add(sig)) continue;

            String srcTitle = nodeRepository.findByCode(rel.getSourceCode())
                    .map(TaxonomyNode::getNameEn).orElse(rel.getSourceCode());
            String tgtTitle = nodeRepository.findByCode(rel.getTargetCode())
                    .map(TaxonomyNode::getNameEn).orElse(rel.getTargetCode());

            RelationHypothesisDto hyp = new RelationHypothesisDto(
                    rel.getSourceCode(), srcTitle,
                    rel.getTargetCode(), tgtTitle,
                    rel.getRelationType(),
                    rel.getPropagatedRelevance(),
                    rel.getIncludedBecause());
            provisionalRelations.add(hyp);
        }
    }

    /**
     * Generates concrete impact relations between leaf nodes from different
     * taxonomy categories. For each trace (root-to-root) relation, finds the
     * most concrete (deepest, highest-scoring) leaf node in each endpoint's
     * category and creates a derived impact relation between them.
     *
     * <p>This ensures the final architecture view prioritizes meaningful
     * cross-category connections (e.g. UA-1574 → CR-1047) over abstract
     * root-level propagation relations (e.g. UA → CR).
     */
    private void generateImpactRelations(List<RequirementElementView> elements,
                                          List<RequirementRelationshipView> relationships) {
        // Group leaf elements by their taxonomy root
        Map<String, List<RequirementElementView>> leafByRoot = new LinkedHashMap<>();
        for (RequirementElementView el : elements) {
            String code = el.getNodeCode();
            if (!code.contains("-")) continue;
            String root = code.substring(0, code.indexOf('-'));
            leafByRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(el);
        }

        // For each trace relation, try to derive a concrete impact relation
        List<RequirementRelationshipView> impactRelations = new ArrayList<>();
        Set<String> impactSignatures = new LinkedHashSet<>();

        for (RequirementRelationshipView trace : relationships) {
            String srcRoot = rootOf(trace.getSourceCode());
            String tgtRoot = rootOf(trace.getTargetCode());
            if (srcRoot == null || tgtRoot == null) continue;
            if (srcRoot.equals(tgtRoot)) continue;

            List<RequirementElementView> srcLeaves = leafByRoot.getOrDefault(srcRoot, List.of());
            List<RequirementElementView> tgtLeaves = leafByRoot.getOrDefault(tgtRoot, List.of());
            if (srcLeaves.isEmpty() || tgtLeaves.isEmpty()) continue;

            // Pick the best leaf in each category (highest relevance, preferring deeper nodes)
            RequirementElementView bestSrc = pickBestLeaf(srcLeaves);
            RequirementElementView bestTgt = pickBestLeaf(tgtLeaves);

            String sig = bestSrc.getNodeCode() + "->" + bestTgt.getNodeCode() + ":" + trace.getRelationType();
            if (!impactSignatures.add(sig)) continue;

            RequirementRelationshipView impact = new RequirementRelationshipView();
            impact.setSourceCode(bestSrc.getNodeCode());
            impact.setTargetCode(bestTgt.getNodeCode());
            impact.setRelationType(trace.getRelationType());
            impact.setPropagatedRelevance(
                    Math.min(bestSrc.getRelevance(), bestTgt.getRelevance()));
            impact.setHopDistance(0);
            impact.setIncludedBecause("impact: " + bestSrc.getNodeCode() + " → " + bestTgt.getNodeCode()
                    + " (derived from " + trace.getSourceCode() + " → " + trace.getTargetCode() + ")");
            impact.setRelationCategory(RequirementRelationshipView.CATEGORY_IMPACT);
            impactRelations.add(impact);
        }

        if (!impactRelations.isEmpty()) {
            relationships.addAll(impactRelations);
            log.info("Generated {} impact relation(s) from {} trace relation(s)",
                    impactRelations.size(), relationships.size() - impactRelations.size());
        }
    }

    /**
     * Returns the taxonomy root code for a node code. For leaf codes like "CP-1023"
     * returns "CP"; for root codes like "CP" returns "CP" as-is.
     */
    private static String rootOf(String code) {
        if (code == null) return null;
        int dash = code.indexOf('-');
        return dash >= 0 ? code.substring(0, dash) : code;
    }

    /**
     * Picks the best leaf node: prefers deeper nodes (longer hierarchy path = more
     * concrete), breaking ties by higher relevance. This ensures the most specific
     * available node is used for impact relations.
     */
    private static RequirementElementView pickBestLeaf(List<RequirementElementView> leaves) {
        return leaves.stream()
                .max(Comparator
                        .comparingInt((RequirementElementView e) -> pathDepth(e.getHierarchyPath()))
                        .thenComparingDouble(RequirementElementView::getRelevance))
                .orElse(leaves.get(0));
    }

    /**
     * Returns the depth of a hierarchy path by counting path segments.
     * E.g. "CP > CP-1000 > CP-1023" → 3, "CP > CP-1000" → 2, "CP" → 1.
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

    /**
     * Builds a hierarchy path string for the given node code using the real
     * taxonomy parent chain (e.g. "CP &gt; CP-1000 &gt; CP-1023").
     * Results are cached in {@code pathCache} to avoid repeated DB lookups
     * within the same request.
     */
    private String buildHierarchyPath(String nodeCode, Map<String, String> pathCache) {
        return pathCache.computeIfAbsent(nodeCode, code -> {
            List<TaxonomyNode> path = taxonomyService.getPathToRoot(code);
            if (path.isEmpty()) {
                return code;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) sb.append(" > ");
                sb.append(path.get(i).getCode());
            }
            return sb.toString();
        });
    }
}
