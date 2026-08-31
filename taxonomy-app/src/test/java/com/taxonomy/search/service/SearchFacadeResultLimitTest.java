package com.taxonomy.search.service;

import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SearchFacadeResultLimitTest {

    @Test
    void returnsEmptyWithoutEmbeddingWorkForNonPositiveSemanticLimits() {
        LocalEmbeddingService embeddingService = mock(LocalEmbeddingService.class);
        SearchFacade facade = facade(embeddingService);

        assertThat(facade.semanticSearch("bounded query", 0)).isEmpty();
        assertThat(facade.semanticSearch("bounded query", -1)).isEmpty();
        assertThat(facade.findSimilarNodes("BP", 0)).isEmpty();
        assertThat(facade.findSimilarNodes("BP", -1)).isEmpty();

        verifyNoInteractions(embeddingService);
    }

    @Test
    void clampsUserControlledSemanticLimitsBeforeEmbeddingSearch() {
        LocalEmbeddingService embeddingService = mock(LocalEmbeddingService.class);
        SearchFacade facade = facade(embeddingService);

        facade.semanticSearch("bounded query", Integer.MAX_VALUE);
        facade.findSimilarNodes("BP", Integer.MAX_VALUE);

        verify(embeddingService).semanticSearch("bounded query", 1_000);
        verify(embeddingService).findSimilarNodes("BP", 1_000);
    }

    @Test
    void preservesRequestedLimitsWithinTheSupportedRange() {
        assertThat(SearchFacade.boundedSemanticResults(1)).isEqualTo(1);
        assertThat(SearchFacade.boundedSemanticResults(25)).isEqualTo(25);
        assertThat(SearchFacade.boundedSemanticResults(1_000)).isEqualTo(1_000);
    }

    private static SearchFacade facade(LocalEmbeddingService embeddingService) {
        return new SearchFacade(
                mock(TaxonomyService.class),
                mock(SearchService.class),
                mock(HybridSearchService.class),
                embeddingService,
                mock(GraphSearchService.class),
                mock(LocalOnnxIndexInitializer.class));
    }
}
