package com.taxonomy.search.service;

import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SearchFacadeResultLimitTest {

    @Test
    void returnsEmptyWithoutSearchWorkForNonPositiveLimits() {
        SearchService searchService = mock(SearchService.class);
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        LocalEmbeddingService embeddingService = mock(LocalEmbeddingService.class);
        SearchFacade facade = facade(searchService, hybridSearchService, embeddingService);

        assertThat(facade.fullTextSearch("bounded query", 0)).isEmpty();
        assertThat(facade.fullTextSearch("bounded query", -1)).isEmpty();
        assertThat(facade.semanticSearch("bounded query", 0)).isEmpty();
        assertThat(facade.semanticSearch("bounded query", -1)).isEmpty();
        assertThat(facade.hybridSearch("bounded query", 0)).isEmpty();
        assertThat(facade.hybridSearch("bounded query", -1)).isEmpty();
        assertThat(facade.findSimilarNodes("BP", 0)).isEmpty();
        assertThat(facade.findSimilarNodes("BP", -1)).isEmpty();

        verifyNoInteractions(searchService, hybridSearchService, embeddingService);
    }

    @Test
    void clampsUserControlledLimitsBeforeSearchDelegation() {
        SearchService searchService = mock(SearchService.class);
        HybridSearchService hybridSearchService = mock(HybridSearchService.class);
        LocalEmbeddingService embeddingService = mock(LocalEmbeddingService.class);
        SearchFacade facade = facade(searchService, hybridSearchService, embeddingService);

        facade.fullTextSearch("bounded query", Integer.MAX_VALUE);
        facade.semanticSearch("bounded query", Integer.MAX_VALUE);
        facade.hybridSearch("bounded query", Integer.MAX_VALUE);
        facade.findSimilarNodes("BP", Integer.MAX_VALUE);

        verify(searchService).search("bounded query", 1_000);
        verify(embeddingService).semanticSearch("bounded query", 1_000);
        verify(hybridSearchService).hybridSearch("bounded query", 1_000);
        verify(embeddingService).findSimilarNodes("BP", 1_000);
    }

    @Test
    void preservesRequestedLimitsWithinTheSupportedRange() {
        assertThat(SearchFacade.boundedResults(1)).isEqualTo(1);
        assertThat(SearchFacade.boundedResults(25)).isEqualTo(25);
        assertThat(SearchFacade.boundedResults(1_000)).isEqualTo(1_000);
    }

    private static SearchFacade facade(SearchService searchService,
                                       HybridSearchService hybridSearchService,
                                       LocalEmbeddingService embeddingService) {
        return new SearchFacade(
                mock(TaxonomyService.class),
                searchService,
                hybridSearchService,
                embeddingService,
                mock(GraphSearchService.class),
                mock(LocalOnnxIndexInitializer.class));
    }
}
