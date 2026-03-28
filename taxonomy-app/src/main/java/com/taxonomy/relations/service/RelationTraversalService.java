package com.taxonomy.relations.service;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.model.RelationType;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import com.taxonomy.catalog.service.TaxonomyRelationService;

/**
 * Loads and filters traversable relations for the architecture view.
 * Only whitelisted relation types are returned.
 *
 * <p>Hierarchy-aware: when a leaf node (e.g. "CO-1023") has no direct
 * relations, the service also returns relations defined for its root
 * code ("CO"). This ensures leaf-level anchors inherit the architecture
 * relations seeded at the root level.
 */
@Service
public class RelationTraversalService {

    /** Relation types allowed for propagation. */
    static final List<RelationType> WHITELISTED_TYPES = List.of(
            RelationType.SUPPORTS,
            RelationType.REALIZES,
            RelationType.USES,
            RelationType.FULFILLS,
            RelationType.DEPENDS_ON
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
     *
     * <p>If the node is a leaf code (e.g. "CO-1023") with no direct relations,
     * also includes relations from its taxonomy root code ("CO").
     */
    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getTraversableRelations(String nodeCode) {
        List<TaxonomyRelationDto> result = new ArrayList<>();
        addRelationsFor(nodeCode, result);

        // Hierarchy fallback: also include root-level relations for leaf nodes
        if (result.isEmpty()) {
            String rootCode = TaxonomyRootTypes.rootFromId(nodeCode);
            if (rootCode != null && !rootCode.equals(nodeCode)) {
                addRelationsFor(rootCode, result);
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

    private void addRelationsFor(String code, List<TaxonomyRelationDto> result) {
        List<TaxonomyRelation> outgoing =
                relationRepository.findBySourceNodeCodeAndRelationTypeIn(code, WHITELISTED_TYPES);
        for (TaxonomyRelation r : outgoing) {
            result.add(relationService.toDto(r));
        }

        List<TaxonomyRelation> incoming =
                relationRepository.findByTargetNodeCodeAndRelationTypeIn(code, WHITELISTED_TYPES);
        for (TaxonomyRelation r : incoming) {
            if (r.isBidirectional()) {
                result.add(relationService.toDto(r));
            }
        }
    }
}
