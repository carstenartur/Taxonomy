package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.SystemRepositoryService;
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
    private static final String SYSTEM_USER = "system";

    private final TaxonomyRelationRepository relationRepository;
    private final TaxonomyNodeRepository nodeRepository;
    private final SystemRepositoryService systemRepositoryService;

    public TaxonomyRelationService(
            TaxonomyRelationRepository relationRepository,
            TaxonomyNodeRepository nodeRepository,
            SystemRepositoryService systemRepositoryService) {
        this.relationRepository = relationRepository;
        this.nodeRepository = nodeRepository;
        this.systemRepositoryService = systemRepositoryService;
    }

    // ── Explicit repository/workspace tenant reads ─────────────────

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsForNode(
            String code, RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<TaxonomyRelation> relations = tenant.workspaceId() == null
                ? relationRepository.findCentralByRepositoryAndNodeCode(
                        tenant.repositoryId(), code)
                : relationRepository.findVisibleByRepositoryAndWorkspaceAndNodeCode(
                        tenant.repositoryId(), tenant.workspaceId(), code);
        return toDtos(relations);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsByType(
            RelationType type, RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<TaxonomyRelation> relations = tenant.workspaceId() == null
                ? relationRepository.findCentralByRepositoryAndRelationType(
                        tenant.repositoryId(), type)
                : relationRepository.findVisibleByRepositoryAndWorkspaceAndRelationType(
                        tenant.repositoryId(), tenant.workspaceId(), type);
        return toDtos(relations);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllRelations(RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<TaxonomyRelation> relations = tenant.workspaceId() == null
                ? relationRepository.findCentralByRepository(tenant.repositoryId())
                : relationRepository.findVisibleByRepositoryAndWorkspace(
                        tenant.repositoryId(), tenant.workspaceId());
        return toDtos(relations);
    }

    /**
     * Return whether an equivalent relation is visible in the selected tenant.
     * Workspace contexts inherit the central baseline of the same repository;
     * central contexts never scan another repository or unpublished workspace.
     */
    @Transactional(readOnly = true)
    public boolean relationExistsVisible(
            String sourceCode,
            String targetCode,
            RelationType type,
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<TaxonomyRelation> existing = tenant.workspaceId() == null
                ? relationRepository.findCentralByRepositoryAndSourceTargetType(
                        tenant.repositoryId(), sourceCode, targetCode, type)
                : relationRepository.findVisibleByRepositoryAndWorkspaceAndSourceTargetType(
                        tenant.repositoryId(),
                        tenant.workspaceId(),
                        sourceCode,
                        targetCode,
                        type);
        return !existing.isEmpty();
    }

    // ── Explicit repository/workspace tenant writes ────────────────

    @Transactional
    public TaxonomyRelationDto createRelation(
            String sourceCode,
            String targetCode,
            RelationType type,
            String description,
            String provenance,
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        TaxonomyNode source = nodeRepository.findByCode(sourceCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Source node not found: " + sourceCode));
        TaxonomyNode target = nodeRepository.findByCode(targetCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Target node not found: " + targetCode));

        if (relationExistsVisible(sourceCode, targetCode, type, tenant)) {
            throw new IllegalArgumentException(String.format(
                    "Relation already exists: %s --[%s]--> %s "
                            + "(repository=%s, workspace=%s)",
                    sourceCode,
                    type,
                    targetCode,
                    tenant.repositoryId(),
                    tenant.workspaceId()));
        }

        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setRepositoryId(tenant.repositoryId());
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(type);
        relation.setDescription(description);
        relation.setProvenance(provenance);
        relation.setWorkspaceId(tenant.workspaceId());
        relation.setOwnerUsername(tenant.username());

        TaxonomyRelation saved = relationRepository.save(relation);
        log.info(
                "Created relation: {} --[{}]--> {} (repository={}, workspace={})",
                sourceCode,
                type,
                targetCode,
                tenant.repositoryId(),
                tenant.workspaceId());
        return toDto(saved);
    }

    /** Delete only from the exact repository and exact mutable workspace scope. */
    @Transactional
    public void deleteRelation(Long id, RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        TaxonomyRelation relation = relationRepository.findByIdInRepositoryWorkspace(
                        tenant.repositoryId(), id, tenant.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Relation not found in active repository/workspace: " + id));
        relationRepository.delete(relation);
        log.info(
                "Deleted relation with id: {} (repository={}, workspace={})",
                id,
                tenant.repositoryId(),
                tenant.workspaceId());
    }

    /** Delete matches only from the exact repository and exact workspace scope. */
    @Transactional
    public void deleteRelationBySourceTargetType(
            String sourceCode,
            String targetCode,
            RelationType type,
            RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        List<TaxonomyRelation> matches = tenant.workspaceId() == null
                ? relationRepository.findCentralByRepositoryAndSourceTargetType(
                        tenant.repositoryId(), sourceCode, targetCode, type)
                : relationRepository
                        .findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                                tenant.repositoryId(),
                                tenant.workspaceId(),
                                sourceCode,
                                targetCode,
                                type);
        if (!matches.isEmpty()) {
            relationRepository.deleteAll(matches);
            log.info(
                    "Deleted {} relation(s): {} --[{}]--> {} "
                            + "(repository={}, workspace={})",
                    matches.size(),
                    sourceCode,
                    type,
                    targetCode,
                    tenant.repositoryId(),
                    tenant.workspaceId());
        }
    }

    @Transactional(readOnly = true)
    public long countRelations(RepositoryContext context) {
        RepositoryContext tenant = requireContext(context);
        return tenant.workspaceId() == null
                ? relationRepository.countCentralByRepository(tenant.repositoryId())
                : relationRepository.countVisibleByRepositoryAndWorkspace(
                        tenant.repositoryId(), tenant.workspaceId());
    }

    // ── Primary-repository compatibility boundary ──────────────────
    // These overloads keep existing internal import/export and analysis callers
    // working while they migrate. They resolve the catalog primary explicitly;
    // no null workspace is ever interpreted as a global repository scope.

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsForNode(
            String code, @Nullable String workspaceId) {
        return getRelationsForNode(code, primaryContext(workspaceId, SYSTEM_USER));
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsByType(
            RelationType type, @Nullable String workspaceId) {
        return getRelationsByType(type, primaryContext(workspaceId, SYSTEM_USER));
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllRelations(@Nullable String workspaceId) {
        return getAllRelations(primaryContext(workspaceId, SYSTEM_USER));
    }

    @Transactional(readOnly = true)
    public boolean relationExistsVisible(
            String sourceCode,
            String targetCode,
            RelationType type,
            @Nullable String workspaceId) {
        return relationExistsVisible(
                sourceCode,
                targetCode,
                type,
                primaryContext(workspaceId, SYSTEM_USER));
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsForNode(String code) {
        return getRelationsForNode(code, (String) null);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsByType(RelationType type) {
        return getRelationsByType(type, (String) null);
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllRelations() {
        return getAllRelations((String) null);
    }

    @Transactional
    public TaxonomyRelationDto createRelation(
            String sourceCode,
            String targetCode,
            RelationType type,
            String description,
            String provenance,
            @Nullable String workspaceId,
            @Nullable String ownerUsername) {
        return createRelation(
                sourceCode,
                targetCode,
                type,
                description,
                provenance,
                primaryContext(workspaceId, ownerUsername));
    }

    @Transactional
    public TaxonomyRelationDto createRelation(
            String sourceCode,
            String targetCode,
            RelationType type,
            String description,
            String provenance) {
        return createRelation(
                sourceCode,
                targetCode,
                type,
                description,
                provenance,
                null,
                SYSTEM_USER);
    }

    @Transactional
    public void deleteRelation(Long id, @Nullable String workspaceId) {
        deleteRelation(id, primaryContext(workspaceId, SYSTEM_USER));
    }

    @Transactional
    public void deleteRelation(Long id) {
        deleteRelation(id, (String) null);
    }

    @Transactional
    public void deleteRelationBySourceTargetType(
            String sourceCode,
            String targetCode,
            RelationType type,
            @Nullable String workspaceId) {
        deleteRelationBySourceTargetType(
                sourceCode,
                targetCode,
                type,
                primaryContext(workspaceId, SYSTEM_USER));
    }

    @Transactional
    public void deleteRelationBySourceTargetType(
            String sourceCode,
            String targetCode,
            RelationType type) {
        deleteRelationBySourceTargetType(sourceCode, targetCode, type, (String) null);
    }

    @Transactional(readOnly = true)
    public long countRelations(@Nullable String workspaceId) {
        return countRelations(primaryContext(workspaceId, SYSTEM_USER));
    }

    @Transactional(readOnly = true)
    public long countRelations() {
        return countRelations((String) null);
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

    private RepositoryContext primaryContext(
            @Nullable String workspaceId, @Nullable String username) {
        SystemRepository primary = systemRepositoryService.getPrimaryRepository();
        String user = username == null || username.isBlank()
                ? SYSTEM_USER
                : username.strip();
        String workspace = workspaceId == null || workspaceId.isBlank()
                ? null
                : workspaceId.strip();
        return workspace == null
                ? RepositoryContext.centralRead(
                        primary.getRepositoryId(), primary.getDefaultBranch(), user)
                : RepositoryContext.workspace(
                        primary.getRepositoryId(),
                        workspace,
                        primary.getDefaultBranch(),
                        user);
    }

    private static RepositoryContext requireContext(RepositoryContext context) {
        if (context == null) {
            throw new IllegalArgumentException("RepositoryContext must not be null");
        }
        return context;
    }

    private List<TaxonomyRelationDto> toDtos(List<TaxonomyRelation> relations) {
        List<TaxonomyRelationDto> dtos = new ArrayList<>(relations.size());
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }
}
