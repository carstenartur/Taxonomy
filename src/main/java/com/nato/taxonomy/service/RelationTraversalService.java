package com.nato.taxonomy.service;

import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.model.TaxonomyRelation;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Loads and filters traversable relations for the architecture view.
 * Only whitelisted relation types are returned.
 */
@Service
public class RelationTraversalService {

    /** Relation types allowed for v1 propagation. */
    static final List<RelationType> WHITELISTED_TYPES = List.of(
            RelationType.SUPPORTS,
            RelationType.REALIZES,
            RelationType.USES
    );

    private final TaxonomyRelationRepository relationRepository;
    private final TaxonomyRelationService relationService;

    public RelationTraversalService(TaxonomyRelationRepository relationRepository,
                                    TaxonomyRelationService relationService) {
        this.relationRepository = relationRepository;
        this.relationService = relationService;
    }

    /**
     * Returns all traversable relations for a given node code,
     * considering both outgoing and incoming (for bidirectional) relations
     * filtered to the whitelisted types.
     */
    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getTraversableRelations(String nodeCode) {
        List<TaxonomyRelationDto> result = new ArrayList<>();

        // Outgoing relations of whitelisted types
        List<TaxonomyRelation> outgoing =
                relationRepository.findBySourceNodeCodeAndRelationTypeIn(nodeCode, WHITELISTED_TYPES);
        for (TaxonomyRelation r : outgoing) {
            result.add(relationService.toDto(r));
        }

        // Incoming relations of whitelisted types where the relation is bidirectional
        List<TaxonomyRelation> incoming =
                relationRepository.findByTargetNodeCodeAndRelationTypeIn(nodeCode, WHITELISTED_TYPES);
        for (TaxonomyRelation r : incoming) {
            if (r.isBidirectional()) {
                result.add(relationService.toDto(r));
            }
        }

        return result;
    }

    /**
     * Returns all relations of the whitelisted types.
     */
    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllTraversableRelations() {
        List<TaxonomyRelation> relations = relationRepository.findByRelationTypeIn(WHITELISTED_TYPES);
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation r : relations) {
            dtos.add(relationService.toDto(r));
        }
        return dtos;
    }
}
