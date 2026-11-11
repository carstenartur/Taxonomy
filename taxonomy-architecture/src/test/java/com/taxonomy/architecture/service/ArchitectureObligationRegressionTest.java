package com.taxonomy.architecture.service;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class ArchitectureObligationRegressionTest {
    @Test void allowedRelationsAloneCreateNoObligation() {
        var view = ArchitectureGapService.compatibilityAssessment("Authored fixture");
        assertThat(view.getTotalGaps()).isZero();
        assertThat(view.getJudgments()).isEmpty();
        assertThat(view.getProfileId()).isEqualTo("compatibility");
    }
    @Test void highScoreDoesNotAcceptModelMembership() {
        var nodes = mock(TaxonomyNodeRepository.class);
        var node = new TaxonomyNode(); node.setCode("fixture-cr"); node.setTaxonomyRoot("CR");
        when(nodes.findByCode("fixture-cr")).thenReturn(Optional.of(node));
        var service = new ArchitectureRecommendationService(mock(ArchitectureGapService.class), nodes,
                mock(com.taxonomy.catalog.service.LocalEmbeddingService.class), mock(WorkspaceDslReadPort.class));
        var rec = service.rankMatches(Map.of("fixture-cr", 99d), "Authored fixture", 50);
        assertThat(rec.getAcceptedElements()).isEmpty();
        assertThat(rec.getMatchedElements()).hasSize(1);
        assertThat(rec.getGapAnalysis().getJudgments()).isEmpty();
    }
}
