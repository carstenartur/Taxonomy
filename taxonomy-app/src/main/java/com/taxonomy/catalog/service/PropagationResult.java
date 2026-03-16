package com.taxonomy.catalog.service;

import com.taxonomy.dto.TaxonomyRelationDto;

import java.util.List;
import java.util.Map;

/**
 * Result of relevance propagation across relations.
 */
public class PropagationResult {

    /** nodeCode → propagated relevance (0.0–1.0) */
    private final Map<String, Double> relevanceMap;

    /** nodeCode → hop distance from the nearest anchor */
    private final Map<String, Integer> hopDistanceMap;

    /** nodeCode → explanation why this node was included */
    private final Map<String, String> reasonMap;

    /** Relations that were actually traversed and used in propagation */
    private final List<TraversedRelation> traversedRelations;

    public PropagationResult(Map<String, Double> relevanceMap,
                             Map<String, Integer> hopDistanceMap,
                             Map<String, String> reasonMap,
                             List<TraversedRelation> traversedRelations) {
        this.relevanceMap = relevanceMap;
        this.hopDistanceMap = hopDistanceMap;
        this.reasonMap = reasonMap;
        this.traversedRelations = traversedRelations;
    }

    public Map<String, Double> getRelevanceMap() { return relevanceMap; }
    public Map<String, Integer> getHopDistanceMap() { return hopDistanceMap; }
    public Map<String, String> getReasonMap() { return reasonMap; }
    public List<TraversedRelation> getTraversedRelations() { return traversedRelations; }

    /**
     * Represents a relation that was traversed during propagation.
     */
    public static class TraversedRelation {
        private final TaxonomyRelationDto relation;
        private final double propagatedRelevance;
        private final int hopDistance;
        private final String reason;

        public TraversedRelation(TaxonomyRelationDto relation, double propagatedRelevance,
                                 int hopDistance, String reason) {
            this.relation = relation;
            this.propagatedRelevance = propagatedRelevance;
            this.hopDistance = hopDistance;
            this.reason = reason;
        }

        public TaxonomyRelationDto getRelation() { return relation; }
        public double getPropagatedRelevance() { return propagatedRelevance; }
        public int getHopDistance() { return hopDistance; }
        public String getReason() { return reason; }
    }
}
