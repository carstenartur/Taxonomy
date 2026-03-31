package com.taxonomy.export;

import com.taxonomy.diagram.DiagramNode;

import java.util.*;

/**
 * Determines the best reroute target for a suppressed node during edge rerouting.
 *
 * <p>Instead of always routing to the single highest-relevance survivor (which
 * can leave other survivors edge-less), this strategy distributes rerouted edges
 * across all survivors in the same layer using a load-balancing approach:
 * <ol>
 *   <li>If the suppressed node is a known parent of a survivor (via parentId chain),
 *       route to the direct descendant.</li>
 *   <li>If multiple survivors exist, route to the highest-scoring survivor that
 *       has not yet received more than {@value #MAX_REROUTES_PER_TARGET} rerouted edges.</li>
 *   <li>Fallback: highest relevance, same as the original behaviour.</li>
 * </ol>
 */
class EdgeRerouteStrategy {

    /** Maximum number of rerouted edges before a survivor is deprioritised. */
    static final int MAX_REROUTES_PER_TARGET = 3;

    private final Map<String, Integer> rerouteCount = new LinkedHashMap<>();

    /**
     * Selects the best reroute target for a suppressed node from the given
     * same-type survivors.
     *
     * @param suppressedNode the original node that was suppressed
     * @param sameTypeSurvivors surviving nodes in the same type/layer
     * @return the target node ID, or empty if no suitable target exists
     */
    Optional<String> selectTarget(DiagramNode suppressedNode, List<DiagramNode> sameTypeSurvivors) {
        if (sameTypeSurvivors == null || sameTypeSurvivors.isEmpty()) {
            return Optional.empty();
        }

        // Strategy 1: Direct descendant (parentId match)
        for (DiagramNode survivor : sameTypeSurvivors) {
            if (suppressedNode.id().equals(survivor.parentId())) {
                return recordAndReturn(survivor.id());
            }
        }

        // Strategy 2: Load-balanced — prefer high-relevance survivors that haven't
        // already accumulated too many rerouted edges
        Optional<DiagramNode> balanced = sameTypeSurvivors.stream()
                .filter(s -> rerouteCount.getOrDefault(s.id(), 0) < MAX_REROUTES_PER_TARGET)
                .max(Comparator.comparingDouble(DiagramNode::relevance));

        if (balanced.isPresent()) {
            return recordAndReturn(balanced.get().id());
        }

        // Strategy 3: Fallback — highest relevance regardless of load
        return sameTypeSurvivors.stream()
                .max(Comparator.comparingDouble(DiagramNode::relevance))
                .map(n -> {
                    rerouteCount.merge(n.id(), 1, Integer::sum);
                    return n.id();
                });
    }

    private Optional<String> recordAndReturn(String id) {
        rerouteCount.merge(id, 1, Integer::sum);
        return Optional.of(id);
    }
}
