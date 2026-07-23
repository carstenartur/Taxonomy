package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class TaxonomyRelationService {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyRelationService.class);

    private final TaxonomyRelationRepository relationRepository;
    private final TaxonomyNodeRepository nodeRepository;

    public TaxonomyRelationService(TaxonomyRelationRepository relationRepository,
                                   TaxonomyNodeRepository nodeRepository) {
        this.relationRepository = relationRepository;
        this.nodeRepository = nodeRepository;
    }

    // ── Workspace-scoped read methods ───────────────────────────────

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsForNode(String code, @Nullable String workspaceId) {
        List<TaxonomyRelation> relations = workspaceId != null
                ? relationRepository.findVisibleByWorkspaceAndNodeCode(workspaceId, code)
                : relationRepository.findSharedByNodeCode(code);
        return toDtos(relations);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsByType(RelationType type, @Nullable String workspaceId) {
        List<TaxonomyRelation> relations = workspaceId != null
                ? relationRepository.findVisibleByWorkspaceAndRelationType(workspaceId, type)
                : relationRepository.findByRelationTypeAndWorkspaceIdIsNull(type);
        return toDtos(relations);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllRelations(@Nullable String workspaceId) {
        List<TaxonomyRelation> relations = workspaceId != null
                ? relationRepository.findVisibleByWorkspace(workspaceId)
                : relationRepository.findByWorkspaceIdIsNull();
        return toDtos(relations);
    }

    /**
     * Returns whether an equivalent relation is already visible in the target
     * workspace. Personal workspaces inherit shared baseline relations, while a
     * null workspace checks shared rows only and never scans personal data.
     */
    @Transactional(readOnly = true)
    public boolean relationExistsVisible(String sourceCode, String targetCode,
                                         RelationType type,
                                         @Nullable String workspaceId) {
        List<TaxonomyRelation> existing = workspaceId != null
                ? relationRepository.findVisibleByWorkspaceAndSourceTargetType(
                        workspaceId, sourceCode, targetCode, type)
                : relationRepository.findSharedBySourceTargetType(sourceCode, targetCode, type);
        return !existing.isEmpty();
    }

    // ── Legacy delegating read methods (shared scope) ───────────────

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsForNode(String code) {
        return getRelationsForNode(code, null);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsByType(RelationType type) {
        return getRelationsByType(type, null);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllRelations() {
        return getAllRelations(null);
    }

    // ── Workspace-scoped write methods ──────────────────────────────

    @Transactional
    public TaxonomyRelationDto createRelation(String sourceCode, String targetCode,
                                              RelationType type, String description,
                                              String provenance,
                                              @Nullable String workspaceId,
                                              @Nullable String ownerUsername) {
        TaxonomyNode source = nodeRepository.findByCode(sourceCode)
                .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceCode));
        TaxonomyNode target = nodeRepository.findByCode(targetCode)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetCode));

        if (relationExistsVisible(sourceCode, targetCode, type, workspaceId)) {
            throw new IllegalArgumentException(String.format(
                    "Relation already exists: %s --[%s]--> %s (workspace=%s)",
                    sourceCode, type, targetCode, workspaceId));
        }

        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(type);
        relation.setDescription(description);
        relation.setProvenance(provenance);
        relation.setWorkspaceId(workspaceId);
        relation.setOwnerUsername(ownerUsername);

        TaxonomyRelation saved = relationRepository.save(relation);
        log.info("Created relation: {} --[{}]--> {} (workspace={})", sourceCode, type, targetCode, workspaceId);
        return toDto(saved);
    }

    /** Legacy overload — creates a shared relation. */
    @Transactional
    public TaxonomyRelationDto createRelation(String sourceCode, String targetCode,
                                              RelationType type, String description,
                                              String provenance) {
        return createRelation(sourceCode, targetCode, type, description, provenance, null, null);
    }

    /**
     * Deletes a relation only when it belongs to the exact active workspace.
     * A null workspace ID means shared scope only; a personal workspace can
     * never delete a shared or foreign-workspace relation by guessing its ID.
     */
    @Transactional
    public void deleteRelation(Long id, @Nullable String workspaceId) {
        TaxonomyRelation relation = relationRepository.findByIdInWorkspace(id, workspaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Relation not found in active workspace: " + id));
        relationRepository.delete(relation);
        log.info("Deleted relation with id: {} (workspace={})", id, workspaceId);
    }

    /** Legacy overload — deletes a shared relation only. */
    @Transactional
    public void deleteRelation(Long id) {
        deleteRelation(id, null);
    }

    /**
     * Deletes relations matching a source, target, type, and exact workspace.
     * A null workspace ID means shared relations only, never all workspaces.
     */
    @Transactional
    public void deleteRelationBySourceTargetType(String sourceCode, String targetCode,
                                                  RelationType type,
                                                  @Nullable String workspaceId) {
        List<TaxonomyRelation> matches = workspaceId != null
                ? relationRepository.findByWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                        workspaceId, sourceCode, targetCode, type)
                : relationRepository.findSharedBySourceTargetType(sourceCode, targetCode, type);
        if (!matches.isEmpty()) {
            relationRepository.deleteAll(matches);
            log.info("Deleted {} relation(s): {} --[{}]--> {} (workspace={})",
                    matches.size(), sourceCode, type, targetCode, workspaceId);
        }
    }

    /** Legacy overload — operates on shared relations only. */
    @Transactional
    public void deleteRelationBySourceTargetType(String sourceCode, String targetCode,
                                                  RelationType type) {
        deleteRelationBySourceTargetType(sourceCode, targetCode, type, null);
    }

    public TaxonomyRelationDto toDto(TaxonomyRelation relation) {
        TaxonomyRelationDto dto = new TaxonomyRelationDto();
        dto.setId(relation.getId());
        dto.setSourceCode(relation.getSourceNode().getCode());
        dto.setSourceName(relation.getSourceNode().getNameEn());
        dto.setTargetCode(relation.getTargetNode().getCode());
        dto.setTargetName(relation.getTargetNode().getNameEn());
        dto.setRelationType(relation.getRelationType().name());
        dto.setDescription(relation.getDescription());
        dto.setProvenance(relation.getProvenance());
        dto.setWeight(relation.getWeight());
        dto.setBidirectional(relation.isBidirectional());
        return dto;
    }

    @Transactional(readOnly = true)
    public long countRelations(@Nullable String workspaceId) {
        return workspaceId != null
                ? relationRepository.countVisibleByWorkspace(workspaceId)
                : relationRepository.countByWorkspaceIdIsNull();
    }

    /** Legacy overload — counts shared relations only. */
    @Transactional(readOnly = true)
    public long countRelations() {
        return countRelations(null);
    }

    private List<TaxonomyRelationDto> toDtos(List<TaxonomyRelation> relations) {
        List<TaxonomyRelationDto> dtos = new ArrayList<>(relations.size());
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }
}
