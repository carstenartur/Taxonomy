package com.taxonomy.search.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.GraphSearchResult;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.error.SearchUnavailableException;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchFacadeReadinessRaceTest {
    @ParameterizedTest
    @ValueSource(strings = {"semantic", "similar", "hybrid", "graph"})
    void failureObservedDuringRetrievalCannotBecomeACompletedResponse(String mode) {
        var embeddings = mock(LocalEmbeddingService.class);
        var hybrid = mock(HybridSearchService.class);
        var graph = mock(GraphSearchService.class);
        var initializer = mock(LocalOnnxIndexInitializer.class);
        var nodeReady = new AtomicBoolean(true);
        var state = new AtomicReference<>(LocalOnnxIndexInitializer.State.READY);
        when(embeddings.isEnabled()).thenReturn(true);
        when(embeddings.isAvailable()).thenReturn(true);
        when(initializer.isNodeSearchReady()).thenAnswer(call -> nodeReady.get());
        when(initializer.getState()).thenAnswer(call -> state.get());
        var facade = new SearchFacade(mock(TaxonomyService.class), mock(SearchService.class),
                hybrid, embeddings, graph, initializer);

        switch (mode) {
            case "semantic" -> when(embeddings.semanticSearch("query", 10)).thenAnswer(call -> {
                nodeReady.set(false); return List.of();
            });
            case "similar" -> when(embeddings.findSimilarNodes("BP", 10)).thenAnswer(call -> {
                nodeReady.set(false); return List.of();
            });
            case "hybrid" -> when(hybrid.hybridSearch("query", 10)).thenAnswer(call -> {
                nodeReady.set(false); return List.of();
            });
            case "graph" -> when(graph.graphSearch("query", 10, WorkspaceContext.SHARED)).thenAnswer(call -> {
                state.set(LocalOnnxIndexInitializer.State.PARTIAL);
                return new GraphSearchResult(List.of(), Map.of(), Map.of(), "completed");
            });
            default -> throw new AssertionError(mode);
        }

        assertThatThrownBy(() -> {
            switch (mode) {
                case "semantic" -> facade.semanticSearch("query", 10);
                case "similar" -> facade.findSimilarNodes("BP", 10);
                case "hybrid" -> facade.hybridSearch("query", 10);
                case "graph" -> facade.graphSearch("query", 10, WorkspaceContext.SHARED);
                default -> throw new AssertionError(mode);
            }
        }).isInstanceOf(SearchUnavailableException.class);
        switch (mode) {
            case "semantic" -> verify(embeddings).semanticSearch("query", 10);
            case "similar" -> verify(embeddings).findSimilarNodes("BP", 10);
            case "hybrid" -> verify(hybrid).hybridSearch("query", 10);
            case "graph" -> verify(graph).graphSearch("query", 10, WorkspaceContext.SHARED);
            default -> throw new AssertionError(mode);
        }
    }
}
