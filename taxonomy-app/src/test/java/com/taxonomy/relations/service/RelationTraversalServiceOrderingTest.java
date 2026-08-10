package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.TaxonomyRelationDto;
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
    void outgoingRelationsAreStableWhenDatabaseReturnsAnotherOrder() {
        TaxonomyRelation later = relation(20L);
        TaxonomyRelation earlier = relation(10L);
        TaxonomyRelationDto laterDto = mock(TaxonomyRelationDto.class);
        TaxonomyRelationDto earlierDto = mock(TaxonomyRelationDto.class);

        when(relationRepository.findBySourceNodeCodeAndRelationTypeIn(
                "CO", RelationTraversalService.WHITELISTED_TYPES))
                .thenReturn(List.of(later, earlier));
        when(relationRepository.findByTargetNodeCodeAndRelationTypeIn(
                "CO", RelationTraversalService.WHITELISTED_TYPES))
                .thenReturn(List.of());
        when(relationService.toDto(earlier)).thenReturn(earlierDto);
        when(relationService.toDto(later)).thenReturn(laterDto);

        assertThat(traversalService.getTraversableRelations("CO"))
                .containsExactly(earlierDto, laterDto);
    }

    @Test
    void allRelationsAreStableAndDoNotMutateImmutableRepositoryResults() {
        TaxonomyRelation later = relation(42L);
        TaxonomyRelation earlier = relation(7L);
        TaxonomyRelationDto laterDto = mock(TaxonomyRelationDto.class);
        TaxonomyRelationDto earlierDto = mock(TaxonomyRelationDto.class);

        when(relationRepository.findByRelationTypeIn(
                RelationTraversalService.WHITELISTED_TYPES))
                .thenReturn(List.of(later, earlier));
        when(relationService.toDto(earlier)).thenReturn(earlierDto);
        when(relationService.toDto(later)).thenReturn(laterDto);

        assertThat(traversalService.getAllTraversableRelations())
                .containsExactly(earlierDto, laterDto);
    }

    private static TaxonomyRelation relation(Long id) {
        TaxonomyRelation relation = new TaxonomyRelation();
        relation.setId(id);
        return relation;
    }
}
