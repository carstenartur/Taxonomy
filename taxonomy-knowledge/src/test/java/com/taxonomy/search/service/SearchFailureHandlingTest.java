package com.taxonomy.search.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchFailureHandlingTest {
    private static final String QUERY = "private-query\r\nforged-log-entry";
    private static final String FAILURE = "private-backend-response";
    private static final String SAFE_MESSAGE = "Search is temporarily unavailable.";

    @Test
    void directNonPositiveLimitsRemainEmptyWithoutModelOrBackendAccess() {
        var embeddings = spy(new LocalEmbeddingService());
        var fullText = new SearchService();
        for (int limit : new int[]{0, -1}) {
            assertThat(embeddings.semanticSearch(QUERY, limit)).isEmpty();
            assertThat(embeddings.findSimilarNodes("BP", limit)).isEmpty();
            assertThat(fullText.search(QUERY, limit)).isEmpty();
        }
        verify(embeddings, never()).isAvailable();
    }

    @Test
    void failedFullTextRequestIsNotAnEmptySuccessfulSearch() {
        var service = new SearchService();
        var manager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", manager);
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenThrow(new IllegalStateException(FAILURE));
            assertThatThrownBy(() -> service.search(QUERY, 10))
                    .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
            search.verify(() -> Search.session(manager));
        }
    }

    @Test
    void failedSemanticBackendRequestIsNotAnEmptySuccessfulSearch() throws Exception {
        var service = availableEmbeddings();
        var manager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", manager);
        doReturn(new float[384]).when(service).embedQuery(QUERY);
        doReturn("test-model-identity").when(service).embeddingIndexKey();
        try (var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(manager)).thenThrow(new IllegalStateException(FAILURE));
            assertThatThrownBy(() -> service.semanticSearch(QUERY, 10))
                    .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
            search.verify(() -> Search.session(manager));
        }
    }

    @Test
    void missingNativeRuntimeIsReportedAsUnavailable() throws Exception {
        var service = availableEmbeddings();
        doThrow(new UnsatisfiedLinkError(FAILURE)).when(service).embedQuery(QUERY);
        assertThatThrownBy(() -> service.semanticSearch(QUERY, 10))
                .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
    }

    @Test
    void interruptedInferenceRetainsCancellationAndDoesNotBecomeAnEmptyResult() throws Exception {
        var service = availableEmbeddings();
        doThrow(new InterruptedException(FAILURE)).when(service).embedQuery(QUERY);
        try {
            assertThatThrownBy(() -> service.semanticSearch(QUERY, 10))
                    .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void modelBecomingUnavailableCannotTurnASemanticRequestIntoSuccess() {
        var service = new LocalEmbeddingService();
        assertThatThrownBy(() -> service.semanticSearch(QUERY, 10))
                .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
        assertThatThrownBy(() -> service.findSimilarNodes("BP", 10))
                .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
    }

    @Test
    void similarNodeBackendFailureIsNotAMissingNode() {
        var service = availableEmbeddings();
        var manager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", manager);
        when(manager.createQuery(anyString(), eq(com.taxonomy.catalog.model.TaxonomyNode.class)))
                .thenThrow(new IllegalStateException(FAILURE));
        assertThatThrownBy(() -> service.findSimilarNodes("BP", 10))
                .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
    }

    @Test
    void failedGraphInferenceIsNotACompletedEmptyGraph() throws Exception {
        var embeddings = mock(LocalEmbeddingService.class);
        when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.embedQuery(QUERY)).thenThrow(new IllegalStateException(FAILURE));
        var service = new GraphSearchService(embeddings);
        assertThatThrownBy(() -> service.graphSearch(QUERY, 10, WorkspaceContext.SHARED))
                .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
    }

    @Test
    void unavailableGraphModelIsNotACompletedEmptyGraph() {
        var service = new GraphSearchService(mock(LocalEmbeddingService.class));
        assertThatThrownBy(() -> service.graphSearch(QUERY, 10, WorkspaceContext.SHARED))
                .isInstanceOf(IllegalStateException.class).hasMessage(SAFE_MESSAGE).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void embeddingsKnownUnavailableBeforeTheRequestRetainFullTextHybridMode(boolean modelAlreadyFailed) {
        var fullText = mock(SearchService.class);
        var embeddings = spy(new LocalEmbeddingService());
        ReflectionTestUtils.setField(embeddings, "embeddingEnabled", modelAlreadyFailed);
        ReflectionTestUtils.setField(embeddings, "modelLoadFailed", modelAlreadyFailed);
        var initializer = mock(com.taxonomy.search.LocalOnnxIndexInitializer.class);
        var node = new TaxonomyNodeDto();
        node.setCode("BP");
        when(fullText.search(QUERY, 10)).thenReturn(List.of(node));
        var facade = new SearchFacade(mock(com.taxonomy.catalog.service.TaxonomyService.class), fullText,
                new HybridSearchService(fullText, embeddings), embeddings,
                mock(GraphSearchService.class), initializer);

        assertThat(embeddings.isEnabled()).isEqualTo(modelAlreadyFailed);
        assertThat(facade.hybridSearch(QUERY, 10))
                .containsExactly(node);
        verify(embeddings, never()).semanticSearch(anyString(), anyInt());
        verifyNoInteractions(initializer);
    }

    private static LocalEmbeddingService availableEmbeddings() {
        var service = spy(new LocalEmbeddingService());
        doReturn(true).when(service).isAvailable();
        return service;
    }
}
