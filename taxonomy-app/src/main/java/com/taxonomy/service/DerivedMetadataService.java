package com.taxonomy.service;

import com.taxonomy.model.TaxonomyNode;
import com.taxonomy.repository.RequirementCoverageRepository;
import com.taxonomy.repository.TaxonomyNodeRepository;
import com.taxonomy.repository.TaxonomyRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes and persists derived graph metadata on {@link TaxonomyNode} entities.
 *
 * <p>Derived metadata includes:
 * <ul>
 *     <li>Incoming/outgoing relation counts</li>
 *     <li>Requirement coverage count</li>
 *     <li>Graph role classification (hub / leaf / bridge / isolated)</li>
 * </ul>
 *
 * <p>These fields are indexed by Hibernate Search and improve search relevance,
 * ranking quality, and enable faceted queries over structural properties.
 */
@Service
public class DerivedMetadataService {

    private static final Logger log = LoggerFactory.getLogger(DerivedMetadataService.class);

    /** Nodes with ≥ this many total relations are classified as hubs. */
    private static final int HUB_THRESHOLD = 5;

    private final TaxonomyNodeRepository nodeRepository;
    private final TaxonomyRelationRepository relationRepository;
    private final RequirementCoverageRepository coverageRepository;

    public DerivedMetadataService(TaxonomyNodeRepository nodeRepository,
                                  TaxonomyRelationRepository relationRepository,
                                  RequirementCoverageRepository coverageRepository) {
        this.nodeRepository = nodeRepository;
        this.relationRepository = relationRepository;
        this.coverageRepository = coverageRepository;
    }

    /**
     * Recompute derived metadata for all taxonomy nodes.
     *
     * @return the number of nodes updated
     */
    @Transactional
    public int recomputeAll() {
        log.info("Recomputing derived metadata for all nodes…");

        Map<String, Integer> incomingCounts = new HashMap<>();
        Map<String, Integer> outgoingCounts = new HashMap<>();
        Map<String, Integer> coverageCounts = new HashMap<>();

        // Count relations
        relationRepository.findAll().forEach(rel -> {
            String srcCode = rel.getSourceNode().getCode();
            String tgtCode = rel.getTargetNode().getCode();
            outgoingCounts.merge(srcCode, 1, Integer::sum);
            incomingCounts.merge(tgtCode, 1, Integer::sum);
        });

        // Count requirement coverage
        coverageRepository.findNodeCodeCountPairs().forEach(pair -> {
            String nodeCode = (String) pair[0];
            long count = (Long) pair[1];
            coverageCounts.put(nodeCode, (int) count);
        });

        List<TaxonomyNode> allNodes = nodeRepository.findAll();
        int updated = 0;

        for (TaxonomyNode node : allNodes) {
            String code = node.getCode();
            int incoming = incomingCounts.getOrDefault(code, 0);
            int outgoing = outgoingCounts.getOrDefault(code, 0);
            int coverage = coverageCounts.getOrDefault(code, 0);
            String role = classifyRole(incoming, outgoing);

            boolean changed = node.getIncomingRelationCount() != incoming
                    || node.getOutgoingRelationCount() != outgoing
                    || node.getRequirementCoverageCount() != coverage
                    || !role.equals(node.getGraphRole());

            if (changed) {
                node.setIncomingRelationCount(incoming);
                node.setOutgoingRelationCount(outgoing);
                node.setRequirementCoverageCount(coverage);
                node.setGraphRole(role);
                updated++;
            }
        }

        nodeRepository.saveAll(allNodes);
        log.info("Derived metadata recomputed: {} nodes updated out of {} total.", updated, allNodes.size());
        return updated;
    }

    /**
     * Classify a node's graph role based on its relation counts.
     *
     * <ul>
     *     <li><b>hub</b>: ≥ {@value HUB_THRESHOLD} total relations</li>
     *     <li><b>bridge</b>: both incoming and outgoing relations, but below hub threshold</li>
     *     <li><b>leaf</b>: only incoming or only outgoing relations</li>
     *     <li><b>isolated</b>: no relations at all</li>
     * </ul>
     */
    public static String classifyRole(int incoming, int outgoing) {
        int total = incoming + outgoing;
        if (total == 0) return "isolated";
        if (total >= HUB_THRESHOLD) return "hub";
        if (incoming > 0 && outgoing > 0) return "bridge";
        return "leaf";
    }
}
