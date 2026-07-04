package com.taxonomy.architecture.pipeline;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.RelationHypothesisDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProvisionalRelationStepTest {

    private final TaxonomyNodeRepository nodeRepository = mock(TaxonomyNodeRepository.class);
    private final TaxonomyService taxonomyService = mock(TaxonomyService.class);
    private final ProvisionalRelationStep step = new ProvisionalRelationStep(nodeRepository, taxonomyService);

    @Test
    void skipsHypothesesWithMissingEndpointCodes() {
        RelationHypothesisDto nullSource = hypothesis(null, "CP");
        RelationHypothesisDto blankTarget = hypothesis("BP", " ");
        ArchitectureViewContext ctx = new ArchitectureViewContext(
                Map.of(),
                "test",
                0,
                List.of(nullSource, blankTarget));

        step.execute(ctx);

        assertThat(ctx.getElements()).isEmpty();
        assertThat(ctx.getRelationships()).isEmpty();
        assertThat(ctx.isUsedProvisional()).isFalse();
        verifyNoInteractions(nodeRepository, taxonomyService);
    }

    @Test
    void marksUsedProvisionalWhenAtLeastOneHypothesisWasAdded() {
        RelationHypothesisDto valid = hypothesis("BP", "CP");
        ArchitectureViewContext ctx = new ArchitectureViewContext(
                Map.of("BP", 80, "CP", 70),
                "test",
                0,
                List.of(valid));
        when(taxonomyService.getPathToRoot("BP")).thenReturn(List.of());
        when(taxonomyService.getPathToRoot("CP")).thenReturn(List.of());

        step.execute(ctx);

        assertThat(ctx.getElements()).hasSize(2);
        assertThat(ctx.getRelationships()).hasSize(1);
        assertThat(ctx.isUsedProvisional()).isTrue();
    }

    private static RelationHypothesisDto hypothesis(String sourceCode, String targetCode) {
        RelationHypothesisDto hypothesis = new RelationHypothesisDto();
        hypothesis.setSourceCode(sourceCode);
        hypothesis.setTargetCode(targetCode);
        hypothesis.setRelationType("supports");
        hypothesis.setConfidence(0.8);
        return hypothesis;
    }
}
