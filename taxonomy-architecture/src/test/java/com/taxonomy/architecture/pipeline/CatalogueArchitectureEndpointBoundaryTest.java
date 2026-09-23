package com.taxonomy.architecture.pipeline;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.PropagationResult;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.CatalogueNodeOrigin;
import com.taxonomy.dto.RelationHypothesisDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogueArchitectureEndpointBoundaryTest {

    @Test
    void localNavigationScoreCannotBecomeArchitectureElement() {
        TaxonomyNode local = node("local:ip:navigation:test", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
        when(repository.findByCode(local.getCode())).thenReturn(Optional.of(local));
        TaxonomyService taxonomy = mock(TaxonomyService.class);
        when(taxonomy.getPathToRoot(local.getCode())).thenReturn(List.of(local));

        ArchitectureViewContext context = new ArchitectureViewContext(
                Map.of(local.getCode(), 90), "requirement", 20, List.of());
        context.setPropagation(new PropagationResult(
                Map.of(local.getCode(), 0.9),
                Map.of(local.getCode(), 0),
                Map.of(local.getCode(), "direct score"),
                List.of()));

        assertThatThrownBy(() -> new ElementBuildStep(repository, taxonomy).apply(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("official");
    }

    @Test
    void provisionalLocalEndpointIsRejectedBeforeArchitectureProjection() {
        TaxonomyNode local = node("local:ip:navigation:test", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        TaxonomyNode official = node("IP-1", CatalogueNodeOrigin.OFFICIAL_SOURCE);
        TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
        when(repository.findByCode(local.getCode())).thenReturn(Optional.of(local));
        when(repository.findByCode(official.getCode())).thenReturn(Optional.of(official));
        TaxonomyService taxonomy = mock(TaxonomyService.class);

        RelationHypothesisDto hypothesis = new RelationHypothesisDto(
                local.getCode(), "Local helper", official.getCode(), "Official",
                "RELATED_TO", 0.9, "test");
        ArchitectureViewContext context = new ArchitectureViewContext(
                Map.of(), "requirement", 20, List.of(hypothesis));

        assertThatThrownBy(() -> new ProvisionalRelationStep(repository, taxonomy).apply(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("official");
    }

    private static TaxonomyNode node(String code, CatalogueNodeOrigin origin) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        node.setTaxonomyRoot("IP");
        node.setCatalogueOrigin(origin);
        return node;
    }
}
