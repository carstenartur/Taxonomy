package com.taxonomy.architecture.service;

import com.taxonomy.dto.*;
import com.taxonomy.relations.model.RequirementCoverage;
import com.taxonomy.relations.repository.RequirementCoverageRepository;
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
    private final RequirementCoverageRepository coverageRepository;

    public EnrichedImpactService(ArchitectureGraphQueryService graphQueryService,
                                 RequirementCoverageRepository coverageRepository) {
        this.graphQueryService = graphQueryService;
        this.coverageRepository = coverageRepository;
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
    public EnrichedChangeImpactView findEnrichedFailureImpact(String nodeCode, int maxHops) {
        // Delegate to the existing failure impact analysis
        ChangeImpactView baseView = graphQueryService.findFailureImpact(nodeCode, maxHops);

        EnrichedChangeImpactView enriched = new EnrichedChangeImpactView();
        enriched.setFailedNodeCode(baseView.getFailedNodeCode());
        enriched.setMaxHops(baseView.getMaxHops());
        enriched.setTraversedRelationships(baseView.getTraversedRelationships());
        enriched.setTotalRelationships(baseView.getTotalRelationships());
        enriched.getNotes().addAll(baseView.getNotes());

        // Enrich directly affected elements
        List<EnrichedImpactElement> enrichedDirect = enrichElements(baseView.getDirectlyAffected());
        enriched.setDirectlyAffected(enrichedDirect);

        // Enrich indirectly affected elements
        List<EnrichedImpactElement> enrichedIndirect = enrichElements(baseView.getIndirectlyAffected());
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

        // Risk score = sum of (requirement count × relevance) for all affected elements
        double risk = 0.0;
        for (EnrichedImpactElement el : enrichedDirect) {
            risk += el.getRequirementCount() * el.getRelevance();
        }
        for (EnrichedImpactElement el : enrichedIndirect) {
            risk += el.getRequirementCount() * el.getRelevance() * 0.5; // indirect = half weight
        }
        enriched.setRiskScore(Math.round(risk * 100.0) / 100.0);

        log.info("Enriched failure impact for {}: {} affected, {} requirements, risk={}",
                nodeCode, enriched.getTotalAffected(),
                enriched.getAffectedRequirements().size(), enriched.getRiskScore());

        return enriched;
    }

    private List<EnrichedImpactElement> enrichElements(List<ImpactElement> elements) {
        List<EnrichedImpactElement> result = new ArrayList<>();
        for (ImpactElement el : elements) {
            EnrichedImpactElement enriched = new EnrichedImpactElement();
            enriched.setNodeCode(el.getNodeCode());
            enriched.setTitle(el.getTitle());
            enriched.setTaxonomySheet(el.getTaxonomySheet());
            enriched.setRelevance(el.getRelevance());
            enriched.setHopDistance(el.getHopDistance());
            enriched.setIncludedBecause(el.getIncludedBecause());

            // Look up requirement coverage for this node
            List<RequirementCoverage> coverages = coverageRepository.findByNodeCode(el.getNodeCode());
            List<String> requirementIds = coverages.stream()
                    .map(RequirementCoverage::getRequirementId)
                    .distinct()
                    .collect(Collectors.toList());
            enriched.setCoveredByRequirements(requirementIds);
            enriched.setRequirementCount(requirementIds.size());

            result.add(enriched);
        }
        return result;
    }
}
