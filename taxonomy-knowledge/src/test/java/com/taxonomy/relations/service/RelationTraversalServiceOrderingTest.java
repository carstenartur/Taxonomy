package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationTraversalServiceOrderingTest {

    private final TaxonomyRelationRepository relationRepository =
            mock(TaxonomyRelationRepository.class);
    private final TaxonomyRelationService relationService =
            mock(TaxonomyRelationService.class);
    private final RelationTraversalService traversalService =
            new RelationTraversalService(relationRepository, relationService);

    @Test
    void strongerRelationTypeWinsAnExactEndpointTieRegardlessOfRowId() {
        TaxonomyRelation depends = relation(
                10L, "CO", "CR", RelationType.DEPENDS_ON);
        TaxonomyRelation supports = relation(
                20L, "CO", "CR", RelationType.SUPPORTS);
        TaxonomyRelationDto dependsDto = mock(TaxonomyRelationDto.class);
        TaxonomyRelationDto supportsDto = mock(TaxonomyRelationDto.class);

        when(relationRepository.findBySourceNodeCodeAndRelationTypeIn(
                "CO", RelationTraversalService.WHITELISTED_TYPES))
                .thenReturn(List.of(depends, supports));
        when(relationRepository.findByTargetNodeCodeAndRelationTypeIn(
                "CO", RelationTraversalService.WHITELISTED_TYPES))
                .thenReturn(List.of());
        when(relationService.toDto(supports)).thenReturn(supportsDto);
        when(relationService.toDto(depends)).thenReturn(dependsDto);

        assertThat(traversalService.getTraversableRelations("CO"))
                .containsExactly(supportsDto, dependsDto);
    }

    @Test
    void architectureLayerBreaksEqualTypeTieWithoutMutatingImmutableResult() {
        TaxonomyRelation communications = relation(
                7L, "UA", "CO", RelationType.USES);
        TaxonomyRelation core = relation(
                42L, "UA", "CR", RelationType.USES);
        TaxonomyRelationDto communicationsDto = mock(TaxonomyRelationDto.class);
        TaxonomyRelationDto coreDto = mock(TaxonomyRelationDto.class);

        when(relationRepository.findByRelationTypeIn(
                RelationTraversalService.WHITELISTED_TYPES))
                .thenReturn(List.of(communications, core));
        when(relationService.toDto(core)).thenReturn(coreDto);
        when(relationService.toDto(communications)).thenReturn(communicationsDto);

        assertThat(traversalService.getAllTraversableRelations())
                .containsExactly(coreDto, communicationsDto);
    }

    private static TaxonomyRelation relation(
            Long id, String sourceCode, String targetCode, RelationType type) {
        TaxonomyNode source = new TaxonomyNode();
        source.setCode(sourceCode);
        TaxonomyNode target = new TaxonomyNode();
        target.setCode(targetCode);
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setId(id);
        relation.setSourceNode(source);
        relation.setTargetNode(target);
        relation.setRelationType(type);
        return relation;
    }
}
