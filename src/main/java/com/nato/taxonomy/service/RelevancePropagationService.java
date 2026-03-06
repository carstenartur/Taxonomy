package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.TaxonomyRelationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Computes propagated relevance scores starting from anchor nodes,
 * traversing whitelisted relations up to a maximum hop distance.
 */
@Service
public class RelevancePropagationService {

    private static final Logger log = LoggerFactory.getLogger(RelevancePropagationService.class);

    /** Maximum number of hops from an anchor node. */
    static final int MAX_HOPS = 2;

    /** Minimum relevance threshold — anything below is discarded. */
    static final double MIN_RELEVANCE = 0.35;

    /** Per-hop decay factor applied after the first hop. */
    static final double HOP_DECAY = 0.70;

    /** Propagation weight per relation type. */
    static final Map<String, Double> TYPE_WEIGHTS = Map.of(
            "REALIZES", 0.80,
            "SUPPORTS", 0.75,
            "USES", 0.65
    );

    private final RelationTraversalService traversalService;

    public RelevancePropagationService(RelationTraversalService traversalService) {
        this.traversalService = traversalService;
    }

    /**
     * Propagates relevance from anchor nodes through traversable relations.
     *
     * @param anchorRelevances map of nodeCode → anchor relevance (directScore / 100.0)
     * @return propagation result with relevance, hop distances, reasons, and traversed relations
     */
    public PropagationResult propagate(Map<String, Double> anchorRelevances) {
        Map<String, Double> relevanceMap = new LinkedHashMap<>(anchorRelevances);
        Map<String, Integer> hopDistanceMap = new LinkedHashMap<>();
        Map<String, String> reasonMap = new LinkedHashMap<>();
        List<PropagationResult.TraversedRelation> traversedRelations = new ArrayList<>();

        // Initialize anchors at hop 0
        for (Map.Entry<String, Double> entry : anchorRelevances.entrySet()) {
            hopDistanceMap.put(entry.getKey(), 0);
            reasonMap.put(entry.getKey(), "direct-match");
        }

        // BFS propagation up to MAX_HOPS
        Set<String> currentFrontier = new LinkedHashSet<>(anchorRelevances.keySet());

        for (int hop = 1; hop <= MAX_HOPS; hop++) {
            Set<String> nextFrontier = new LinkedHashSet<>();

            for (String nodeCode : currentFrontier) {
                double sourceRelevance = relevanceMap.getOrDefault(nodeCode, 0.0);
                if (sourceRelevance < MIN_RELEVANCE) {
                    continue;
                }

                List<TaxonomyRelationDto> relations = traversalService.getTraversableRelations(nodeCode);

                for (TaxonomyRelationDto rel : relations) {
                    String targetCode = determineTarget(rel, nodeCode);
                    if (targetCode == null) continue;

                    Double typeWeight = TYPE_WEIGHTS.get(rel.getRelationType());
                    if (typeWeight == null) continue;

                    double propagated = sourceRelevance * typeWeight;
                    // Apply additional hop decay for hops beyond the first
                    if (hop > 1) {
                        propagated *= HOP_DECAY;
                    }

                    if (propagated < MIN_RELEVANCE) continue;

                    // Keep highest relevance if multiple paths lead to the same node
                    double existing = relevanceMap.getOrDefault(targetCode, 0.0);
                    if (propagated > existing) {
                        relevanceMap.put(targetCode, propagated);
                        hopDistanceMap.put(targetCode, hop);
                        reasonMap.put(targetCode,
                                "propagated via " + rel.getRelationType() + " from " + nodeCode);
                        nextFrontier.add(targetCode);

                        log.debug("Propagated {} → {} via {} = {} (hop {})",
                                nodeCode, targetCode, rel.getRelationType(), propagated, hop);
                    }

                    // Record the traversed relation
                    String relReason = "propagated via " + rel.getRelationType() + " from " + nodeCode;
                    traversedRelations.add(new PropagationResult.TraversedRelation(
                            rel, propagated, hop, relReason));
                }
            }

            currentFrontier = nextFrontier;
            if (currentFrontier.isEmpty()) break;
        }

        log.info("Propagation complete: {} anchor(s), {} relation(s) traversed, {} element(s) included, maxHop={}",
                anchorRelevances.size(), traversedRelations.size(), relevanceMap.size(),
                hopDistanceMap.values().stream().mapToInt(Integer::intValue).max().orElse(0));

        return new PropagationResult(relevanceMap, hopDistanceMap, reasonMap, traversedRelations);
    }

    /**
     * Determines the target node code given a relation and the current source.
     * For outgoing relations from nodeCode, the target is the relation's target.
     * For bidirectional relations where nodeCode is the target, the target becomes the source.
     */
    private String determineTarget(TaxonomyRelationDto rel, String nodeCode) {
        if (rel.getSourceCode().equals(nodeCode)) {
            return rel.getTargetCode();
        }
        if (rel.getTargetCode().equals(nodeCode) && rel.isBidirectional()) {
            return rel.getSourceCode();
        }
        return null;
    }
}
