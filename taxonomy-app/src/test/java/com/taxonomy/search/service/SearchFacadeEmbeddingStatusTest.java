package com.taxonomy.search.service;

import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchFacadeEmbeddingStatusTest {

    @Mock private TaxonomyService taxonomyService;
    @Mock private SearchService searchService;
    @Mock private HybridSearchService hybridSearchService;
    @Mock private LocalEmbeddingService embeddingService;
    @Mock private GraphSearchService graphSearchService;
    @Mock private LocalOnnxIndexInitializer indexInitializer;

    private SearchFacade facade;

    @BeforeEach
    void setUp() {
        facade = new SearchFacade(
                taxonomyService,
                searchService,
                hybridSearchService,
                embeddingService,
                graphSearchService,
                indexInitializer);
    }

    @Test
    void modelWithoutSearchableNodeIndexIsNotReportedAvailable() {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.effectiveModelUrl()).thenReturn("/models");
        when(embeddingService.indexedNodeCount()).thenReturn(0);
        when(indexInitializer.isNodeSearchReady()).thenReturn(false);
        when(indexInitializer.getState())
                .thenReturn(LocalOnnxIndexInitializer.State.INDEXING_NODES);
        when(indexInitializer.getDetail()).thenReturn("Building taxonomy-node vectors");

        var status = facade.getEmbeddingStatus();

        assertThat(status)
                .containsEntry("enabled", true)
                .containsEntry("modelAvailable", true)
                .containsEntry("available", false)
                .containsEntry("semanticReady", false)
                .containsEntry("indexState", "INDEXING_NODES")
                .containsEntry("indexedNodes", 0);
    }

    @Test
    void nodeIndexReadinessEnablesSemanticSearchBeforeRelationCompletion() {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.effectiveModelUrl()).thenReturn("/models");
        when(embeddingService.indexedNodeCount()).thenReturn(309);
        when(indexInitializer.isNodeSearchReady()).thenReturn(true);
        when(indexInitializer.getState())
                .thenReturn(LocalOnnxIndexInitializer.State.INDEXING_RELATIONS);
        when(indexInitializer.getDetail()).thenReturn("Node semantic search is ready");
        when(indexInitializer.getIndexedNodesAtReadiness()).thenReturn(309);

        var status = facade.getEmbeddingStatus();

        assertThat(facade.isSemanticSearchReady()).isTrue();
        assertThat(status)
                .containsEntry("available", true)
                .containsEntry("semanticReady", true)
                .containsEntry("indexState", "INDEXING_RELATIONS")
                .containsEntry("indexedNodesAtReadiness", 309);
    }
}
