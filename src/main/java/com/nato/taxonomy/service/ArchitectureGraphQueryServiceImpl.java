package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.*;
import com.nato.taxonomy.model.TaxonomyNode;
import com.nato.taxonomy.model.TaxonomyRelation;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.repository.TaxonomyNodeRepository;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ArchitectureGraphQueryService}.
 *
 * <p>Traverses taxonomy relations using BFS with relevance decay per hop.
 * Only whitelisted relation types (SUPPORTS, REALIZES, USES) are traversed.
 */
@Service
public class ArchitectureGraphQueryServiceImpl implements ArchitectureGraphQueryService {

    private static final Logger log = LoggerFactory.getLogger(ArchitectureGraphQueryServiceImpl.class);

    /** Maximum allowed hops (clamped). */
    private static final int MAX_HOP_LIMIT = 5;

    /** Minimum relevance threshold — anything below is discarded. */
    static final double MIN_RELEVANCE = 0.20;

    /** Per-hop decay factor. */
    static final double HOP_DECAY = 0.70;

    /** Anchor threshold for requirement impact (same as architecture view). */
    private static final int ANCHOR_THRESHOLD_HIGH = 70;
    private static final int ANCHOR_THRESHOLD_LOW = 50;
    private static final int MIN_ANCHORS = 3;

    /**
     * Propagation weights per relation type.
     * SUPPORTS propagates strongly (service-to-process),
     * REALIZES medium (capability-to-service),
     * USES weaker (app-to-service).
     */
    static final Map<String, Double> TYPE_WEIGHTS = Map.of(
            "SUPPORTS", 0.80,
            "REALIZES", 0.75,
            "USES", 0.60
    );

    /** Whitelisted relation types for traversal. */
    private static final List<RelationType> WHITELISTED_TYPES = List.of(
            RelationType.SUPPORTS,
            RelationType.REALIZES,
            RelationType.USES
    );

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;
    private final TaxonomyRelationService relationService;

    public ArchitectureGraphQueryServiceImpl(TaxonomyNodeRepository nodeRepository,
                                              TaxonomyRelationRepository relationRepository,
                                              TaxonomyRelationService relationService) {
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
        this.relationService = relationService;
    }

    // ── 1. Requirement Impact ──────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public RequirementImpactView findImpactForRequirement(Map<String, Integer> scores,
                                                           String businessText, int maxHops) {
        RequirementImpactView view = new RequirementImpactView();
        view.setBusinessText(businessText);
        int clampedHops = clampHops(maxHops);
        view.setMaxHops(clampedHops);

        if (scores == null || scores.isEmpty()) {
            view.getNotes().add("No scores provided; impact analysis cannot be performed.");
            return view;
        }

        // Select anchors using the same logic as RequirementArchitectureViewService
        List<Map.Entry<String, Integer>> anchors = selectAnchors(scores);
        if (anchors.isEmpty()) {
            view.getNotes().add("No nodes met the anchor threshold; impact view is empty.");
            return view;
        }

        // Build anchor relevance map
        Map<String, Double> anchorRelevances = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : anchors) {
            anchorRelevances.put(entry.getKey(), entry.getValue() / 100.0);
        }

        // BFS traversal from anchors
        TraversalResult traversal = bfsTraversal(anchorRelevances, clampedHops, Direction.BOTH);

        // Convert to view elements
        view.setImpactedElements(toImpactElements(traversal));
        view.setTraversedRelationships(traversal.relationships);
        view.setTotalElements(view.getImpactedElements().size());
        view.setTotalRelationships(view.getTraversedRelationships().size());

        if (view.getImpactedElements().isEmpty()) {
            view.getNotes().add("Anchors were found but no connected elements via accepted relations.");
        }

        log.info("Requirement impact: {} anchors, {} elements, {} relationships (maxHops={})",
                anchors.size(), view.getTotalElements(), view.getTotalRelationships(), clampedHops);

        return view;
    }

    // ── 2. Upstream / Downstream ───────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public GraphNeighborhoodView findUpstream(String nodeCode, int maxHops) {
        return findNeighborhood(nodeCode, maxHops, Direction.UPSTREAM);
    }

    @Override
    @Transactional(readOnly = true)
    public GraphNeighborhoodView findDownstream(String nodeCode, int maxHops) {
        return findNeighborhood(nodeCode, maxHops, Direction.DOWNSTREAM);
    }

    private GraphNeighborhoodView findNeighborhood(String nodeCode, int maxHops, Direction direction) {
        GraphNeighborhoodView view = new GraphNeighborhoodView();
        view.setOriginNodeCode(nodeCode);
        view.setDirection(direction.name());
        int clampedHops = clampHops(maxHops);
        view.setMaxHops(clampedHops);

        Optional<TaxonomyNode> originOpt = nodeRepository.findByCode(nodeCode);
        if (originOpt.isEmpty()) {
            view.getNotes().add("Node not found: " + nodeCode);
            return view;
        }

        Map<String, Double> startMap = Map.of(nodeCode, 1.0);
        TraversalResult traversal = bfsTraversal(startMap, clampedHops, direction);

        // Remove the origin node from results
        List<ImpactElement> neighbors = toImpactElements(traversal).stream()
                .filter(e -> !e.getNodeCode().equals(nodeCode))
                .collect(Collectors.toList());

        view.setNeighbors(neighbors);
        view.setTraversedRelationships(traversal.relationships);
        view.setTotalNeighbors(neighbors.size());
        view.setTotalRelationships(traversal.relationships.size());

        if (neighbors.isEmpty()) {
            view.getNotes().add("No " + direction.name().toLowerCase() + " neighbors found for " + nodeCode);
        }

        log.info("{} query for {}: {} neighbors, {} relationships (maxHops={})",
                direction, nodeCode, neighbors.size(), traversal.relationships.size(), clampedHops);

        return view;
    }

    // ── 3. Failure / Change Impact ─────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ChangeImpactView findFailureImpact(String nodeCode, int maxHops) {
        ChangeImpactView view = new ChangeImpactView();
        view.setFailedNodeCode(nodeCode);
        int clampedHops = clampHops(maxHops);
        view.setMaxHops(clampedHops);

        Optional<TaxonomyNode> nodeOpt = nodeRepository.findByCode(nodeCode);
        if (nodeOpt.isEmpty()) {
            view.getNotes().add("Node not found: " + nodeCode);
            return view;
        }

        // Failure propagates through both directions (anything connected is at risk)
        Map<String, Double> startMap = Map.of(nodeCode, 1.0);
        TraversalResult traversal = bfsTraversal(startMap, clampedHops, Direction.BOTH);

        List<ImpactElement> allAffected = toImpactElements(traversal).stream()
                .filter(e -> !e.getNodeCode().equals(nodeCode))
                .collect(Collectors.toList());

        // Split into directly affected (hop 1) and indirectly affected (hop 2+)
        List<ImpactElement> direct = allAffected.stream()
                .filter(e -> e.getHopDistance() == 1)
                .collect(Collectors.toList());
        List<ImpactElement> indirect = allAffected.stream()
                .filter(e -> e.getHopDistance() > 1)
                .collect(Collectors.toList());

        view.setDirectlyAffected(direct);
        view.setIndirectlyAffected(indirect);
        view.setTraversedRelationships(traversal.relationships);
        view.setTotalAffected(allAffected.size());
        view.setTotalRelationships(traversal.relationships.size());

        if (allAffected.isEmpty()) {
            view.getNotes().add("No connected elements found for " + nodeCode);
        }

        log.info("Failure impact for {}: {} direct, {} indirect, {} relationships (maxHops={})",
                nodeCode, direct.size(), indirect.size(), traversal.relationships.size(), clampedHops);

        return view;
    }

    // ── Internal Traversal Engine ──────────────────────────────────────────

    private enum Direction { UPSTREAM, DOWNSTREAM, BOTH }

    /**
     * Intermediate result from BFS traversal.
     */
    private static class TraversalResult {
        final Map<String, Double> relevanceMap;
        final Map<String, Integer> hopMap;
        final Map<String, String> reasonMap;
        final List<ImpactRelationship> relationships;

        TraversalResult(Map<String, Double> relevanceMap, Map<String, Integer> hopMap,
                        Map<String, String> reasonMap, List<ImpactRelationship> relationships) {
            this.relevanceMap = relevanceMap;
            this.hopMap = hopMap;
            this.reasonMap = reasonMap;
            this.relationships = relationships;
        }
    }

    /**
     * Generic BFS traversal with relevance propagation and direction control.
     */
    private TraversalResult bfsTraversal(Map<String, Double> startRelevances,
                                          int maxHops, Direction direction) {
        Map<String, Double> relevanceMap = new LinkedHashMap<>(startRelevances);
        Map<String, Integer> hopMap = new LinkedHashMap<>();
        Map<String, String> reasonMap = new LinkedHashMap<>();
        List<ImpactRelationship> relationships = new ArrayList<>();

        // Initialize start nodes
        for (Map.Entry<String, Double> entry : startRelevances.entrySet()) {
            hopMap.put(entry.getKey(), 0);
            reasonMap.put(entry.getKey(), "origin");
        }

        Set<String> currentFrontier = new LinkedHashSet<>(startRelevances.keySet());
        Set<Long> visitedRelationIds = new HashSet<>();

        for (int hop = 1; hop <= maxHops; hop++) {
            Set<String> nextFrontier = new LinkedHashSet<>();

            for (String nodeCode : currentFrontier) {
                double sourceRelevance = relevanceMap.getOrDefault(nodeCode, 0.0);
                if (sourceRelevance < MIN_RELEVANCE) continue;

                List<TaxonomyRelationDto> relations = getDirectedRelations(nodeCode, direction);

                for (TaxonomyRelationDto rel : relations) {
                    String targetCode = determineTarget(rel, nodeCode, direction);
                    if (targetCode == null) continue;

                    Double typeWeight = TYPE_WEIGHTS.get(rel.getRelationType());
                    if (typeWeight == null) continue;

                    double propagated = sourceRelevance * typeWeight;
                    if (hop > 1) {
                        propagated *= HOP_DECAY;
                    }

                    if (propagated < MIN_RELEVANCE) continue;

                    double existing = relevanceMap.getOrDefault(targetCode, 0.0);
                    if (propagated > existing) {
                        relevanceMap.put(targetCode, propagated);
                        hopMap.put(targetCode, hop);
                        reasonMap.put(targetCode,
                                "propagated via " + rel.getRelationType() + " from " + nodeCode);
                        nextFrontier.add(targetCode);
                    }

                    // Record traversed relationship (deduplicate by relation ID)
                    if (rel.getId() != null && visitedRelationIds.add(rel.getId())) {
                        ImpactRelationship ir = new ImpactRelationship();
                        ir.setRelationId(rel.getId());
                        ir.setSourceCode(rel.getSourceCode());
                        ir.setTargetCode(rel.getTargetCode());
                        ir.setRelationType(rel.getRelationType());
                        ir.setPropagatedRelevance(propagated);
                        ir.setHopDistance(hop);
                        relationships.add(ir);
                    }
                }
            }

            currentFrontier = nextFrontier;
            if (currentFrontier.isEmpty()) break;
        }

        return new TraversalResult(relevanceMap, hopMap, reasonMap, relationships);
    }

    /**
     * Gets relations for a node in the specified direction.
     */
    private List<TaxonomyRelationDto> getDirectedRelations(String nodeCode, Direction direction) {
        List<TaxonomyRelationDto> result = new ArrayList<>();

        if (direction == Direction.DOWNSTREAM || direction == Direction.BOTH) {
            // Outgoing: this node is the source
            List<TaxonomyRelation> outgoing =
                    relationRepository.findBySourceNodeCodeAndRelationTypeIn(nodeCode, WHITELISTED_TYPES);
            for (TaxonomyRelation r : outgoing) {
                result.add(relationService.toDto(r));
            }
        }

        if (direction == Direction.UPSTREAM || direction == Direction.BOTH) {
            // Incoming: this node is the target
            List<TaxonomyRelation> incoming =
                    relationRepository.findByTargetNodeCodeAndRelationTypeIn(nodeCode, WHITELISTED_TYPES);
            for (TaxonomyRelation r : incoming) {
                result.add(relationService.toDto(r));
            }
        }

        // Also add bidirectional relations traversed in the opposite direction
        if (direction == Direction.DOWNSTREAM || direction == Direction.BOTH) {
            List<TaxonomyRelation> incomingBidir =
                    relationRepository.findByTargetNodeCodeAndRelationTypeIn(nodeCode, WHITELISTED_TYPES);
            for (TaxonomyRelation r : incomingBidir) {
                if (r.isBidirectional()) {
                    result.add(relationService.toDto(r));
                }
            }
        }

        if (direction == Direction.UPSTREAM || direction == Direction.BOTH) {
            List<TaxonomyRelation> outgoingBidir =
                    relationRepository.findBySourceNodeCodeAndRelationTypeIn(nodeCode, WHITELISTED_TYPES);
            for (TaxonomyRelation r : outgoingBidir) {
                if (r.isBidirectional()) {
                    result.add(relationService.toDto(r));
                }
            }
        }

        return result;
    }

    /**
     * Determines the traversal target given a relation and direction.
     */
    private String determineTarget(TaxonomyRelationDto rel, String nodeCode, Direction direction) {
        switch (direction) {
            case DOWNSTREAM:
                // Follow outgoing edges: source → target
                if (rel.getSourceCode().equals(nodeCode)) {
                    return rel.getTargetCode();
                }
                // Bidirectional incoming can be traversed in reverse
                if (rel.getTargetCode().equals(nodeCode) && rel.isBidirectional()) {
                    return rel.getSourceCode();
                }
                return null;

            case UPSTREAM:
                // Follow incoming edges: target ← source
                if (rel.getTargetCode().equals(nodeCode)) {
                    return rel.getSourceCode();
                }
                // Bidirectional outgoing can be traversed in reverse
                if (rel.getSourceCode().equals(nodeCode) && rel.isBidirectional()) {
                    return rel.getTargetCode();
                }
                return null;

            case BOTH:
            default:
                if (rel.getSourceCode().equals(nodeCode)) {
                    return rel.getTargetCode();
                }
                if (rel.getTargetCode().equals(nodeCode)) {
                    return rel.getSourceCode();
                }
                return null;
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private List<ImpactElement> toImpactElements(TraversalResult traversal) {
        List<ImpactElement> elements = new ArrayList<>();

        for (Map.Entry<String, Double> entry : traversal.relevanceMap.entrySet()) {
            String code = entry.getKey();
            ImpactElement el = new ImpactElement();
            el.setNodeCode(code);
            el.setRelevance(entry.getValue());
            el.setHopDistance(traversal.hopMap.getOrDefault(code, 0));
            el.setIncludedBecause(traversal.reasonMap.getOrDefault(code, "unknown"));

            // Lookup title and taxonomy sheet
            nodeRepository.findByCode(code).ifPresent(node -> {
                el.setTitle(node.getNameEn());
                el.setTaxonomySheet(node.getTaxonomyRoot());
            });

            elements.add(el);
        }

        // Sort by relevance descending
        elements.sort(Comparator.comparingDouble(ImpactElement::getRelevance).reversed());

        return elements;
    }

    private List<Map.Entry<String, Integer>> selectAnchors(Map<String, Integer> scores) {
        // Sort by score descending
        List<Map.Entry<String, Integer>> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toList());

        // Prefer all nodes above HIGH threshold
        List<Map.Entry<String, Integer>> highAnchors = sorted.stream()
                .filter(e -> e.getValue() >= ANCHOR_THRESHOLD_HIGH)
                .collect(Collectors.toList());

        if (highAnchors.size() >= MIN_ANCHORS) {
            return highAnchors;
        }

        // Fallback: top-N above LOW threshold
        List<Map.Entry<String, Integer>> lowAnchors = sorted.stream()
                .filter(e -> e.getValue() >= ANCHOR_THRESHOLD_LOW)
                .limit(MIN_ANCHORS)
                .collect(Collectors.toList());

        return lowAnchors;
    }

    private int clampHops(int maxHops) {
        return Math.max(1, Math.min(maxHops, MAX_HOP_LIMIT));
    }
}
