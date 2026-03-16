package com.taxonomy.catalog.service;

import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsForNode(String code) {
        List<TaxonomyRelation> relations =
                relationRepository.findBySourceNodeCodeOrTargetNodeCode(code, code);
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getRelationsByType(RelationType type) {
        List<TaxonomyRelation> relations = relationRepository.findByRelationType(type);
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }

    @Transactional(readOnly = true)
    public List<TaxonomyRelationDto> getAllRelations() {
        List<TaxonomyRelation> relations = relationRepository.findAll();
        List<TaxonomyRelationDto> dtos = new ArrayList<>();
        for (TaxonomyRelation relation : relations) {
            dtos.add(toDto(relation));
        }
        return dtos;
    }

    @Transactional
    public TaxonomyRelationDto createRelation(String sourceCode, String targetCode,
                                              RelationType type, String description,
                                              String provenance) {
        TaxonomyNode source = nodeRepository.findByCode(sourceCode)
                .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceCode));
        TaxonomyNode target = nodeRepository.findByCode(targetCode)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetCode));

        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(type);
        relation.setDescription(description);
        relation.setProvenance(provenance);

        TaxonomyRelation saved = relationRepository.save(relation);
        log.info("Created relation: {} --[{}]--> {}", sourceCode, type, targetCode);
        return toDto(saved);
    }

    @Transactional
    public void deleteRelation(Long id) {
        relationRepository.deleteById(id);
        log.info("Deleted relation with id: {}", id);
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
    public long countRelations() {
        return relationRepository.count();
    }
}
