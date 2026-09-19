package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RelationCandidateServiceFilterTest {
    private final HybridSearchService search = mock(HybridSearchService.class);
    private final RelationCompatibilityMatrix matrix = mock(RelationCompatibilityMatrix.class);
    private final RelationCandidateService service = new RelationCandidateService(
            search, mock(LocalEmbeddingService.class), matrix);

    @Test
    void excludesSelfAndWrongRootsBeforeApplyingLimitWithoutChangingRanking() {
        var source = source("Detailed description");
        var first = dto("CP-1", "CP");
        var second = dto("CP-2", "CP");
        when(search.hybridSearch("Source Detailed description", 2))
                .thenReturn(List.of(dto("BP-1", "BP"), dto("WRONG", "IP"), first, second));
        when(matrix.allowedTargetRoots("BP", RelationType.SUPPORTS)).thenReturn(Set.of("CP"));
        assertThat(service.findCandidates(source, RelationType.SUPPORTS, 1)).containsExactly(first);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void absentDescriptionDoesNotPolluteQueryAndUnrestrictedRootsAllowOtherTaxonomies(String description) {
        var target = dto("IP-1", "IP");
        when(search.hybridSearch("Source", 4)).thenReturn(List.of(dto("BP-1", "BP"), target));
        when(matrix.allowedTargetRoots("BP", RelationType.RELATED_TO)).thenReturn(Set.of());
        assertThat(service.findCandidates(source(description), RelationType.RELATED_TO, 2)).containsExactly(target);
        verify(search).hybridSearch("Source", 4);
    }

    private static TaxonomyNode source(String description) {
        var node = new TaxonomyNode();
        node.setCode("BP-1");
        node.setTaxonomyRoot("BP");
        node.setNameEn("Source");
        node.setDescriptionEn(description);
        return node;
    }
    private static TaxonomyNodeDto dto(String code, String root) {
        var node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setTaxonomyRoot(root);
        return node;
    }
}
