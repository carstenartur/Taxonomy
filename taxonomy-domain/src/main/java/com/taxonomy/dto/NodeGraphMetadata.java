package com.taxonomy.dto;

/**
 * Derived graph metadata for a taxonomy node.
 *
 * <p>Exposes computed structural properties that are useful for
 * search ranking, recommendations, and impact analysis.
 *
 * @param nodeCode               the taxonomy node code
 * @param incomingRelationCount  number of incoming relations
 * @param outgoingRelationCount  number of outgoing relations
 * @param totalRelationCount     total relations (incoming + outgoing)
 * @param requirementCoverageCount number of requirements covering this node
 * @param graphRole              classified role: hub, bridge, leaf, or isolated
 */
public record NodeGraphMetadata(
        String nodeCode,
        int incomingRelationCount,
        int outgoingRelationCount,
        int totalRelationCount,
        int requirementCoverageCount,
        String graphRole
) {}
