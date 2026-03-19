package com.taxonomy.catalog.service;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
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
        List<TaxonomyRelation> relations;
        if (workspaceId != null) {
            relations = relationRepository.findByWorkspaceAndNodeCode(workspaceId, code);
        } else {
            relations = relationRepository.findBySourceNodeCodeOrTargetNodeCode(code, code);
        }
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsByType(RelationType type, @Nullable String workspaceId) {
        List<TaxonomyRelation> relations;
        if (workspaceId != null) {
            relations = relationRepository.findByWorkspaceAndRelationType(workspaceId, type);
        } else {
            relations = relationRepository.findByRelationType(type);
        }
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllRelations(@Nullable String workspaceId) {
        List<TaxonomyRelation> relations;
        if (workspaceId != null) {
            relations = relationRepository.findByWorkspaceIdIsNullOrWorkspaceId(workspaceId);
        } else {
            relations = relationRepository.findAll();
        }
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }

    // ── Legacy delegating read methods (backward-compatible) ─────────

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

        // Programmatic duplicate check (covers NULL workspace_id in unique constraint)
        List<TaxonomyRelation> existing;
        if (workspaceId != null) {
            existing = relationRepository.findByWorkspaceAndSourceTargetType(
                    workspaceId, sourceCode, targetCode, type);
        } else {
            existing = relationRepository.findBySourceNodeCodeAndTargetNodeCodeAndRelationType(
                    sourceCode, targetCode, type);
            // Filter to only those with null workspace for exact match
            existing = existing.stream()
                    .filter(r -> r.getWorkspaceId() == null)
                    .toList();
        }
        if (!existing.isEmpty()) {
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

    /**
     * Legacy overload — delegates to workspace-aware method with null workspace.
     */
    @Transactional
    public TaxonomyRelationDto createRelation(String sourceCode, String targetCode,
                                              RelationType type, String description,
                                              String provenance) {
        return createRelation(sourceCode, targetCode, type, description, provenance, null, null);
    }

    /**
     * Delete a relation by ID, with optional workspace ownership check.
     *
     * <p>If {@code workspaceId} is non-null, the relation must either belong to
     * the given workspace or be a shared (null workspace) relation. If it belongs
     * to a different workspace, an {@link IllegalArgumentException} is thrown.
     */
    @Transactional
    public void deleteRelation(Long id, @Nullable String workspaceId) {
        if (workspaceId != null) {
            TaxonomyRelation relation = relationRepository.findById(id).orElse(null);
            if (relation != null && relation.getWorkspaceId() != null
                    && !workspaceId.equals(relation.getWorkspaceId())) {
                throw new IllegalArgumentException(
                        "Relation " + id + " belongs to workspace '" + relation.getWorkspaceId()
                                + "', not '" + workspaceId + "'");
            }
        }
        relationRepository.deleteById(id);
        log.info("Deleted relation with id: {} (workspace={})", id, workspaceId);
    }

    /** Legacy overload — deletes without workspace ownership check. */
    @Transactional
    public void deleteRelation(Long id) {
        deleteRelation(id, null);
    }

    /**
     * Delete all relations matching a specific source, target, and type combination.
     * Used by the proposal revert mechanism to remove accepted relations.
     */
    @Transactional
    public void deleteRelationBySourceTargetType(String sourceCode, String targetCode,
                                                  RelationType type) {
        List<TaxonomyRelation> matches = relationRepository
                .findBySourceNodeCodeAndTargetNodeCodeAndRelationType(sourceCode, targetCode, type);
        if (!matches.isEmpty()) {
            relationRepository.deleteAll(matches);
            log.info("Deleted {} relation(s): {} --[{}]--> {}", matches.size(), sourceCode, type, targetCode);
        }
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
        if (workspaceId != null) {
            return relationRepository.countByWorkspaceIdIsNullOrWorkspaceId(workspaceId);
        }
        return relationRepository.count();
    }

    /** Legacy overload — counts all relations. */
    @Transactional(readOnly = true)
    public long countRelations() {
        return countRelations(null);
    }
}
