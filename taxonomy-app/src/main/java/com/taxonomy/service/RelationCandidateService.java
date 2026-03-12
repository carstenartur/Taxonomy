package com.taxonomy.service;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.model.TaxonomyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Finds candidate target nodes for a proposed relation using hybrid search
 * (full-text + vector similarity).  High recall is the goal at this stage;
 * precision is handled downstream by validation and scoring.
 */
@Service
public class RelationCandidateService {

    private static final Logger log = LoggerFactory.getLogger(RelationCandidateService.class);

    private final HybridSearchService hybridSearchService;
    private final LocalEmbeddingService embeddingService;
    private final RelationCompatibilityMatrix compatibilityMatrix;

    public RelationCandidateService(HybridSearchService hybridSearchService,
                                    LocalEmbeddingService embeddingService,
                                    RelationCompatibilityMatrix compatibilityMatrix) {
        this.hybridSearchService = hybridSearchService;
        this.embeddingService = embeddingService;
        this.compatibilityMatrix = compatibilityMatrix;
    }

    /**
     * Find candidate target nodes for the given source node and relation type.
     *
     * @param source       the source taxonomy node
     * @param relationType the desired relation type
     * @param limit        maximum number of candidates to return
     * @return candidate nodes ranked by relevance
     */
    public List<TaxonomyNodeDto> findCandidates(TaxonomyNode source,
                                                 RelationType relationType,
                                                 int limit) {
        String queryText = buildQueryText(source);
        log.debug("Finding candidates for {} [{}] with query: '{}'",
                source.getCode(), relationType, queryText);

        // Use hybrid search for high recall
        List<TaxonomyNodeDto> hybridResults = hybridSearchService.hybridSearch(queryText, limit * 2);

        // Filter by allowed target taxonomy roots for this relation type
        Set<String> allowedRoots = compatibilityMatrix.allowedTargetRoots(
                source.getTaxonomyRoot(), relationType);

        List<TaxonomyNodeDto> filtered = hybridResults.stream()
                .filter(dto -> !dto.getCode().equals(source.getCode())) // exclude self
                .filter(dto -> allowedRoots.isEmpty() || allowedRoots.contains(dto.getTaxonomyRoot()))
                .limit(limit)
                .collect(Collectors.toList());

        log.debug("Candidates for {} [{}]: {} found, {} after filtering",
                source.getCode(), relationType, hybridResults.size(), filtered.size());
        return filtered;
    }

    private String buildQueryText(TaxonomyNode source) {
        StringBuilder sb = new StringBuilder();
        sb.append(source.getNameEn());
        if (source.getDescriptionEn() != null && !source.getDescriptionEn().isBlank()) {
            sb.append(' ').append(source.getDescriptionEn());
        }
        return sb.toString();
    }
}
