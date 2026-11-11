package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Authored unit fixtures; no model or catalogue quality claim. */
class RelationMetamodelRegressionTest {
    @Test void validatorAndServiceAgreeForEveryDeclaredEndpointFamily() {
        var matrix = new RelationCompatibilityMatrix();
        for (var type : RelationType.values()) for (String sourceRoot : com.taxonomy.dsl.model.TaxonomyRootTypes.ROOT_TO_TYPE.keySet())
            for (String targetRoot : com.taxonomy.dsl.model.TaxonomyRootTypes.ROOT_TO_TYPE.keySet()) {
                var model = new com.taxonomy.dsl.model.CanonicalArchitectureModel();
                model.getElements().add(new com.taxonomy.dsl.model.ArchitectureElement("source", sourceRoot, "Source", null, null));
                model.getElements().add(new com.taxonomy.dsl.model.ArchitectureElement("target", targetRoot, "Target", null, null));
                model.getRelations().add(new com.taxonomy.dsl.model.ArchitectureRelation("source", type.name(), "target"));
                boolean validatorAllows = new com.taxonomy.dsl.validation.DslValidator().validate(model).getWarnings().isEmpty();
                assertThat(matrix.isCompatible(sourceRoot, targetRoot, type)).as("%s %s -> %s", type, sourceRoot, targetRoot).isEqualTo(validatorAllows);
            }
    }
    @Test void forbiddenSourceDoesNotRetrieveCandidates() {
        var search = mock(HybridSearchService.class);
        when(search.hybridSearch(anyString(), anyInt())).thenReturn(List.of());
        var service = new RelationCandidateService(search, mock(LocalEmbeddingService.class), new RelationCompatibilityMatrix());
        var source = new TaxonomyNode(); source.setCode("fixture-ip"); source.setNameEn("Information"); source.setTaxonomyRoot("IP");
        assertThat(service.findCandidates(source, RelationType.REALIZES, 5)).isEmpty();
        verifyNoInteractions(search);
    }
    @Test void unspecifiedRelationTypeCannotGrantPermission() {
        var matrix = new RelationCompatibilityMatrix();
        assertThat(matrix.targetDomain("BP", null).state())
                .isEqualTo(com.taxonomy.dsl.validation.RelationMetamodel.DomainState.FORBIDDEN);
        assertThat(matrix.isCompatible("BP", "IP", null)).isFalse();
    }
    @Test void relatedToRemainsUnrestricted() {
        var search = mock(HybridSearchService.class);
        var target = new TaxonomyNodeDto(); target.setCode("fixture-bp"); target.setTaxonomyRoot("BP");
        when(search.hybridSearch(anyString(), anyInt())).thenReturn(List.of(target));
        var service = new RelationCandidateService(search, mock(LocalEmbeddingService.class), new RelationCompatibilityMatrix());
        var source = new TaxonomyNode(); source.setCode("fixture-ip"); source.setNameEn("Information"); source.setTaxonomyRoot("IP");
        assertThat(service.findCandidates(source, RelationType.RELATED_TO, 5)).containsExactly(target);
    }
}
