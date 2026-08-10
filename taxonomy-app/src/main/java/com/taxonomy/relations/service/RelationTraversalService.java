package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.dsl.model.TaxonomyRootTypes;
import com.taxonomy.model.RelationType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    /**
     * Stable persistence order used for deterministic architecture views and exports.
     *
     * <p>JPQL without an explicit order has no ordering contract and PostgreSQL may
     * legitimately return the same rows in a different order after a schema/index
     * change. Relation IDs reflect the reviewed/imported persistence order and retain
     * the established diagram/readme contract across supported databases.</p>
     */
    private static final Comparator<TaxonomyRelation> PERSISTENCE_ORDER =
            Comparator.comparing(
                    TaxonomyRelation::getId,
                    Comparator.nullsLast(Comparator.naturalOrder()));

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
     * Returns all relations of the whitelisted types in stable persistence order.
     */
    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllTraversableRelations() {
        List<TaxonomyRelation> relations = new ArrayList<>(
                relationRepository.findByRelationTypeIn(WHITELISTED_TYPES));
        relations.sort(PERSISTENCE_ORDER);
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation relation : relations) {
            dtos.add(relationService.toDto(relation));
        }
        return dtos;
    }

    private void addRelationsFor(String code, List<TaxonomyRelationDto> result) {
        List<TaxonomyRelation> outgoing = new ArrayList<>(
                relationRepository.findBySourceNodeCodeAndRelationTypeIn(
                        code, WHITELISTED_TYPES));
        outgoing.sort(PERSISTENCE_ORDER);
        for (TaxonomyRelation relation : outgoing) {
            result.add(relationService.toDto(relation));
        }

        List<TaxonomyRelation> incoming = new ArrayList<>(
                relationRepository.findByTargetNodeCodeAndRelationTypeIn(
                        code, WHITELISTED_TYPES));
        incoming.sort(PERSISTENCE_ORDER);
        for (TaxonomyRelation relation : incoming) {
            if (relation.isBidirectional()) {
                result.add(relationService.toDto(relation));
            }
        }
    }
}
