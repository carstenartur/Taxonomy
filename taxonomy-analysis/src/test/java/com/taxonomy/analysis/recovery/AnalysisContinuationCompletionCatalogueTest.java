package com.taxonomy.analysis.recovery;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service boundary paired with the real persisted seed/fallback cases in RecoveryCheckpointPersistenceTest. */
class AnalysisContinuationCompletionCatalogueTest {
    private final AnalysisContinuationStore store = mock(AnalysisContinuationStore.class);
    private final TaxonomyService catalogue = mock(TaxonomyService.class);
    private final AnalysisContinuationService service = new AnalysisContinuationService(
            store, catalogue, null, null, new ObjectMapper());
    private final AnalysisContinuationStore.Claim claim = new AnalysisContinuationStore.Claim("run", "claim", null);

    @Test
    void completionCanUseItsOriginalEvidenceWhenTheLiveCatalogueIsUnavailable() {
        var incoming = new AnalysisResult();
        var canonical = new AnalysisResult(Map.of("BP", 90.0), tree("Original catalogue"));
        when(catalogue.getFullTree()).thenThrow(new IllegalStateException("Live catalogue unavailable"));
        when(store.completeWithFallback(eq(claim), same(incoming), any())).thenReturn(canonical);

        try (var execution = service.new Execution(claim)) {
            assertThat(execution.complete(incoming)).isSameAs(canonical);
            assertThat(canonical.getTree().getFirst().getNameEn()).isEqualTo("Original catalogue");
        }
        verifyNoInteractions(catalogue);
        verify(store, never()).interrupted(any());
    }

    @Test
    void legacyCompletionCanStillResolveTheLiveCatalogueWhenTheStoreNeedsIt() {
        var incoming = new AnalysisResult();
        var fallback = tree("Current catalogue");
        when(catalogue.getFullTree()).thenReturn(fallback);
        when(store.completeWithFallback(eq(claim), same(incoming), any())).thenAnswer(call -> {
            Supplier<List<TaxonomyNodeDto>> load = call.getArgument(2);
            incoming.setTree(load.get());
            return incoming;
        });

        try (var execution = service.new Execution(claim)) {
            assertThat(execution.complete(incoming)).isSameAs(incoming);
            assertThat(incoming.getTree()).isSameAs(fallback);
        }
        verify(catalogue).getFullTree();
        verify(store, never()).interrupted(any());
    }

    @Test
    void aSupersededClaimFailsBeforeConsultingAnyLiveCatalogue() {
        var incoming = new AnalysisResult();
        when(catalogue.getFullTree()).thenThrow(new IllegalStateException("Live catalogue unavailable"));
        when(store.completeWithFallback(eq(claim), same(incoming), any())).thenThrow(
                new ResponseStatusException(HttpStatus.CONFLICT, "Operation claim was superseded"));

        try (var execution = service.new Execution(claim)) {
            assertThatThrownBy(() -> execution.complete(incoming)).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("409").hasMessageContaining("superseded");
        }
        verifyNoInteractions(catalogue);
        verify(store).interrupted(claim);
    }

    private static List<TaxonomyNodeDto> tree(String name) {
        var node = new TaxonomyNodeDto();
        node.setCode("BP"); node.setTaxonomyRoot("BP"); node.setNameEn(name);
        return List.of(node);
    }
}
