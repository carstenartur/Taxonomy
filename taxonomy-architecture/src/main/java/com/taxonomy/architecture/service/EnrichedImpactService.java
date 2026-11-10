package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.architecture.service.GraphSnapshotReadService.SnapshotMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;
import com.taxonomy.dto.ChangeImpactView;
import com.taxonomy.dto.EnrichedChangeImpactView;
import com.taxonomy.dto.EnrichedImpactElement;
import com.taxonomy.dto.ImpactElement;

/**
 * Enriches the existing failure/change impact analysis with requirement
 * coverage data. For each affected element, the service looks up which
 * requirements cover it and computes an aggregated risk score.
 */
@Service
public class EnrichedImpactService {

    private static final Logger log = LoggerFactory.getLogger(EnrichedImpactService.class);

    private final ArchitectureGraphQueryService graphQueryService;
    private final GraphSnapshotReadService snapshots;

    public EnrichedImpactService(ArchitectureGraphQueryService graphQueryService,
                                 GraphSnapshotReadService snapshots) {
        this.graphQueryService = graphQueryService;
        this.snapshots = snapshots;
    }

    /**
     * Computes failure impact for the given node and enriches results with
     * requirement coverage correlation.
     *
     * @param nodeCode the node that fails or changes
     * @param maxHops  maximum graph hops (1–5, clamped)
     * @return enriched change impact view with requirement correlation
     */
    @Transactional(readOnly = true)
    public EnrichedChangeImpactView findEnrichedFailureImpact(RepositoryContext context, String commitId, String nodeCode, int maxHops) {
        var snapshot = snapshots.read(context, commitId);
        ChangeImpactView baseView = graphQueryService.findFailureImpact(snapshot, nodeCode, maxHops);
        var mappings = snapshot.mappings();
        EnrichedChangeImpactView enriched = new EnrichedChangeImpactView();
        enriched.setEvidence(baseView.getEvidence());
        enriched.setFailedNodeCode(baseView.getFailedNodeCode());
        enriched.setMaxHops(baseView.getMaxHops());
        enriched.setTraversedRelationships(baseView.getTraversedRelationships());
        enriched.setTotalRelationships(baseView.getTotalRelationships());
        enriched.getNotes().addAll(baseView.getNotes());

        // Enrich directly affected elements
        List<EnrichedImpactElement> enrichedDirect = enrichElements(baseView.getDirectlyAffected(), mappings);
        enriched.setDirectlyAffected(enrichedDirect);

        // Enrich indirectly affected elements
        List<EnrichedImpactElement> enrichedIndirect = enrichElements(baseView.getIndirectlyAffected(), mappings);
        enriched.setIndirectlyAffected(enrichedIndirect);

        enriched.setTotalAffected(enrichedDirect.size() + enrichedIndirect.size());

        // Collect all distinct affected requirements
        Set<String> allRequirements = new LinkedHashSet<>();
        for (EnrichedImpactElement el : enrichedDirect) {
            allRequirements.addAll(el.getCoveredByRequirements());
        }
        for (EnrichedImpactElement el : enrichedIndirect) {
            allRequirements.addAll(el.getCoveredByRequirements());
        }
        enriched.setAffectedRequirements(new ArrayList<>(allRequirements));

        List<String> uncorrelated = java.util.stream.Stream.concat(enrichedDirect.stream(), enrichedIndirect.stream())
                .filter(element -> element.getRequirementCount() == 0)
                .map(EnrichedImpactElement::getNodeCode).distinct().sorted().toList();
        enriched.setUncorrelatedElementIds(uncorrelated);
        boolean hasRelevantMappings = !allRequirements.isEmpty();
        boolean fullyCorrelated = hasRelevantMappings && uncorrelated.isEmpty();
        enriched.setCorrelationCompleteness(!hasRelevantMappings ? "INCONCLUSIVE"
                : fullyCorrelated ? "RECORDED_MAPPINGS_ONLY" : "PARTIAL_RECORDED_MAPPINGS");

        // Risk score = sum of (requirement count × relevance) for all affected elements
        double risk = 0.0;
        for (EnrichedImpactElement el : enrichedDirect) {
            risk += el.getRequirementCount() * el.getRelevance();
        }
        for (EnrichedImpactElement el : enrichedIndirect) {
            risk += el.getRequirementCount() * el.getRelevance() * 0.5; // indirect = half weight
        }
        enriched.setRiskScore(fullyCorrelated ? Math.round(risk * 100.0) / 100.0 : null);
        enriched.getNotes().add("Requirement correlation uses only mappings recorded in this exact commit; missing mappings do not establish missing coverage.");
        snapshots.verifyHead(snapshot);

        log.info("Enriched failure impact for {}: {} affected, {} requirements, risk={}",
                nodeCode, enriched.getTotalAffected(),
                enriched.getAffectedRequirements().size(), enriched.getRiskScore());

        return enriched;
    }

    private List<EnrichedImpactElement> enrichElements(List<ImpactElement> elements, List<SnapshotMapping> mappings) {
        List<EnrichedImpactElement> result = new ArrayList<>();
        for (ImpactElement el : elements) {
            EnrichedImpactElement enriched = new EnrichedImpactElement();
            enriched.setNodeCode(el.getNodeCode());
            enriched.setNodeEvidence(el.getNodeEvidence());
            enriched.setTitle(el.getTitle());
            enriched.setTaxonomySheet(el.getTaxonomySheet());
            enriched.setRelevance(el.getRelevance());
            enriched.setHopDistance(el.getHopDistance());
            enriched.setIncludedBecause(el.getIncludedBecause());

            // Look up requirement coverage for this node
            List<String> requirementIds = mappings.stream()
                    .filter(mapping -> mapping.elementId().equals(el.getNodeCode()))
                    .map(SnapshotMapping::requirementId)
                    .distinct()
                    .collect(Collectors.toList());
            enriched.setCoveredByRequirements(requirementIds);
            enriched.setRequirementCount(requirementIds.size());

            result.add(enriched);
        }
        return result;
    }
}
