package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
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
import java.util.Map;

/**
 * Loads and filters traversable relations for the architecture view.
 * Only whitelisted relation types are returned.
 *
 * <p>Hierarchy-aware: when a leaf node (e.g. "CO-1023") has no direct
 * relations, the service also returns relations defined for its root
 * code ("CO"). This ensures leaf-level anchors inherit the architecture
 * relations seeded at the root level.</p>
 */
@Service
public class RelationTraversalService {

    /** Relation types allowed for propagation in their canonical traversal order. */
    static final List<RelationType> WHITELISTED_TYPES = List.of(
            RelationType.SUPPORTS,
            RelationType.REALIZES,
            RelationType.USES,
            RelationType.FULFILLS,
            RelationType.DEPENDS_ON
    );

    private static final Map<RelationType, Integer> RELATION_TYPE_ORDER = Map.of(
            RelationType.SUPPORTS, 10,
            RelationType.REALIZES, 20,
            RelationType.USES, 30,
            RelationType.FULFILLS, 40,
            RelationType.DEPENDS_ON, 50);

    /**
     * Canonical architectural layer order used only as a deterministic tie breaker.
     * Values are intentionally distinct even where diagram layers are visually shared.
     */
    private static final Map<String, Integer> ROOT_ORDER = Map.of(
            "CP", 10,
            "BP", 20,
            "BR", 21,
            "CR", 30,
            "CI", 31,
            "UA", 40,
            "IP", 50,
            "CO", 60);

    /**
     * Total semantic ordering independent of database plans and generated row IDs.
     *
     * <p>Equal propagation paths can otherwise make the selected explanation and
     * derived impact-edge order depend on the database's physical scan order. IDs
     * are retained only as a final tie breaker for genuinely duplicate in-memory
     * objects, not as the architecture semantics.</p>
     */
    private static final Comparator<TaxonomyRelation> RELATION_ORDER = Comparator
            .comparingInt((TaxonomyRelation relation) ->
                    RELATION_TYPE_ORDER.getOrDefault(relation.getRelationType(), Integer.MAX_VALUE))
            .thenComparingInt(relation -> rootOrder(sourceCode(relation)))
            .thenComparingInt(relation -> rootOrder(targetCode(relation)))
            .thenComparing(RelationTraversalService::sourceCode,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(RelationTraversalService::targetCode,
                    Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TaxonomyRelation::getId,
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
     * also includes relations from its taxonomy root code ("CO").</p>
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

    /** Returns all relations of the whitelisted types in canonical semantic order. */
    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllTraversableRelations() {
        List<TaxonomyRelation> relations = new ArrayList<>(
                relationRepository.findByRelationTypeIn(WHITELISTED_TYPES));
        relations.sort(RELATION_ORDER);
        List<TaxonomyRelationDto> dtos = new ArrayList<>(relations.size());
        for (TaxonomyRelation relation : relations) {
            dtos.add(relationService.toDto(relation));
        }
        return dtos;
    }

    private void addRelationsFor(String code, List<TaxonomyRelationDto> result) {
        List<TaxonomyRelation> outgoing = new ArrayList<>(
                relationRepository.findBySourceNodeCodeAndRelationTypeIn(
                        code, WHITELISTED_TYPES));
        outgoing.sort(RELATION_ORDER);
        for (TaxonomyRelation relation : outgoing) {
            result.add(relationService.toDto(relation));
        }

        List<TaxonomyRelation> incoming = new ArrayList<>(
                relationRepository.findByTargetNodeCodeAndRelationTypeIn(
                        code, WHITELISTED_TYPES));
        incoming.sort(RELATION_ORDER);
        for (TaxonomyRelation relation : incoming) {
            if (relation.isBidirectional()) {
                result.add(relationService.toDto(relation));
            }
        }
    }

    private static int rootOrder(String code) {
        String root = rootOf(code);
        return ROOT_ORDER.getOrDefault(root, Integer.MAX_VALUE);
    }

    private static String rootOf(String code) {
        if (code == null) {
            return null;
        }
        int separator = code.indexOf('-');
        return separator >= 0 ? code.substring(0, separator) : code;
    }

    private static String sourceCode(TaxonomyRelation relation) {
        TaxonomyNode source = relation.getSourceNode();
        return source != null ? source.getCode() : null;
    }

    private static String targetCode(TaxonomyRelation relation) {
        TaxonomyNode target = relation.getTargetNode();
        return target != null ? target.getCode() : null;
    }
}
