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

class CatalogueAuthorityProjectionContractTest {

    @Test
    void localScoredNodeCannotBecomeArchitectureElement() {
        TaxonomyNode local = node("local:ip:reports", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
        TaxonomyService taxonomy = mock(TaxonomyService.class);
        when(repository.findByCode(local.getCode())).thenReturn(Optional.of(local));
        when(taxonomy.getPathToRoot(local.getCode())).thenReturn(List.of(local));

        ArchitectureViewContext context =
                new ArchitectureViewContext(Map.of(local.getCode(), 90), "requirement", 20, null);
        context.setPropagation(new PropagationResult(
                Map.of(local.getCode(), 0.9),
                Map.of(local.getCode(), 0),
                Map.of(local.getCode(), "direct"),
                List.of()));

        ElementBuildStep step = new ElementBuildStep(repository, taxonomy);
        assertThatThrownBy(() -> step.apply(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("official");
    }

    @Test
    void localProvisionalEndpointCannotBeInjectedAsArchitectureElement() {
        TaxonomyNode local = node("local:ip:reports", CatalogueNodeOrigin.LOCAL_NAVIGATION);
        TaxonomyNode official = node("BP-1", CatalogueNodeOrigin.OFFICIAL_SOURCE);
        TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
        TaxonomyService taxonomy = mock(TaxonomyService.class);
        when(repository.findByCode(local.getCode())).thenReturn(Optional.of(local));
        when(repository.findByCode(official.getCode())).thenReturn(Optional.of(official));
        when(taxonomy.getPathToRoot(local.getCode())).thenReturn(List.of(local));
        when(taxonomy.getPathToRoot(official.getCode())).thenReturn(List.of(official));

        RelationHypothesisDto hypothesis = new RelationHypothesisDto(
                official.getCode(), official.getNameEn(),
                local.getCode(), local.getNameEn(),
                "PRODUCES", 0.9, "provisional");
        ArchitectureViewContext context = new ArchitectureViewContext(
                Map.of(official.getCode(), 80, local.getCode(), 90),
                "requirement", 20, List.of(hypothesis));

        ProvisionalRelationStep step = new ProvisionalRelationStep(repository, taxonomy);
        assertThatThrownBy(() -> step.apply(context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("official");
    }

    private static TaxonomyNode node(String code, CatalogueNodeOrigin origin) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        node.setTaxonomyRoot(code.startsWith("BP") ? "BP" : "IP");
        node.setCatalogueOrigin(origin);
        return node;
    }
}
