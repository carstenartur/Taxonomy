package com.taxonomy.catalog.service;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.persistence.EntityManager;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import java.nio.file.Path;
import java.nio.file.Files;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.relations.service.GraphSearchService;
import com.taxonomy.relations.service.HybridSearchService;
import com.taxonomy.workspace.service.WorkspaceContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class SearchDiagnosticPrivacyTest {

    private static final String QUERY = "private-business-query\r\nforged-log-entry";
    private static final String FAILURE = "private-backend-response-token";

    @Test
    void lazyModelFailureDoesNotLogConfiguredPathsOrRawExceptions(@TempDir Path temporary) throws Exception {
        var service = new LocalEmbeddingService();
        var missingModel = Files.createDirectory(temporary.resolve("private-model-path"));
        ReflectionTestUtils.setField(service, "embeddingEnabled", true);
        ReflectionTestUtils.setField(service, "modelDir", missingModel.toString());
        try (var capture = new LogCapture(LocalEmbeddingService.class)) {
            assertThatThrownBy(() -> service.semanticSearch(QUERY, 10))
                    .isInstanceOf(com.taxonomy.search.SearchUnavailableException.class).hasNoCause();
            assertThat(capture.events.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-", "forged-log-entry");
                assertThat(event.getThrowableProxy()).isNull();
                if (event.getArgumentArray() != null) {
                    assertThat(event.getArgumentArray()).allSatisfy(argument ->
                            assertThat(String.valueOf(argument)).doesNotContain("private-", "forged-log-entry"));
                }
            });
        }
    }

    @Test
    void successfulHybridSearchDoesNotLogTheBusinessQueryAtDebugLevel() {
        var fullText = mock(SearchService.class);
        var embeddings = mock(LocalEmbeddingService.class);
        var node = new TaxonomyNodeDto();
        node.setCode("BP");
        when(fullText.search(QUERY, 10)).thenReturn(List.of(node));
        when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.semanticSearch(QUERY, 10)).thenReturn(List.of(node));
        try (var capture = new LogCapture(HybridSearchService.class)) {
            assertThat(new HybridSearchService(fullText, embeddings).hybridSearch(QUERY, 10))
                    .containsExactly(node);
            capture.assertSafeEvent(Level.DEBUG, "semantic=1, fullText=1, fused=1");
        }
    }

    @Test
    void graphFailureDiagnosticsExcludeInferencePayloadAndNestedCause() throws Exception {
        var embeddings = mock(LocalEmbeddingService.class);
        when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.embedQuery(QUERY)).thenThrow(
                new IllegalStateException(FAILURE, new IOException("private-nested-cause")));
        try (var capture = new LogCapture(GraphSearchService.class)) {
            org.assertj.core.api.Assertions.catchThrowable(() ->
                    new GraphSearchService(embeddings).graphSearch(QUERY, 10, WorkspaceContext.SHARED));
            capture.assertSafeError("GRAPH_SEARCH_FAILED");
        }
    }

    @Test
    void similarNodeFailureDiagnosticsExcludeUntrustedNodeCodeAndBackendPayload() {
        var service = spy(new LocalEmbeddingService());
        doReturn(true).when(service).isAvailable();
        var manager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", manager);
        when(manager.createQuery(anyString(), eq(com.taxonomy.catalog.model.TaxonomyNode.class)))
                .thenThrow(new IllegalStateException(FAILURE));
        try (var capture = new LogCapture(LocalEmbeddingService.class)) {
            org.assertj.core.api.Assertions.catchThrowable(() -> service.findSimilarNodes(QUERY, 10));
            capture.assertSafeError("SIMILAR_SEARCH_FAILED");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingSimilarNodeRemainsAnEmptyResultWithoutLoggingTheUntrustedCode() {
        var service = spy(new LocalEmbeddingService());
        doReturn(true).when(service).isAvailable();
        var manager = mock(EntityManager.class);
        var query = (jakarta.persistence.TypedQuery<com.taxonomy.catalog.model.TaxonomyNode>)
                mock(jakarta.persistence.TypedQuery.class);
        ReflectionTestUtils.setField(service, "entityManager", manager);
        when(manager.createQuery(anyString(), eq(com.taxonomy.catalog.model.TaxonomyNode.class)))
                .thenReturn(query);
        when(query.setParameter("code", QUERY)).thenReturn(query);
        when(query.getResultStream()).thenReturn(Stream.empty());
        try (var capture = new LogCapture(LocalEmbeddingService.class)) {
            assertThat(service.findSimilarNodes(QUERY, 10)).isEmpty();
            capture.assertSafeEvent(Level.WARN, "SIMILAR_NODE_NOT_FOUND");
        }
    }

    @Test
    void fullTextBackendFailureRemainsExplicitWithoutLoggingQueryOrFailurePayload() {
        var service = new SearchService();
        var entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        try (var capture = new LogCapture(SearchService.class);
             var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(entityManager)).thenThrow(
                    new IllegalStateException(FAILURE, new IOException("private-nested-cause")));

            assertThatThrownBy(() -> service.search(QUERY, 10))
                    .isInstanceOf(com.taxonomy.search.SearchUnavailableException.class).hasNoCause();

            capture.assertSafeError("SEARCH_BACKEND_FAILED");
        }
    }

    @Test
    void blankFullTextQueryReturnsWithoutAccessingTheBackendOrLogging() {
        var service = new SearchService();
        try (var capture = new LogCapture(SearchService.class);
             var search = mockStatic(Search.class)) {
            assertThat(service.search("  ", 10)).isEmpty();
            assertThat(service.search(null, 10)).isEmpty();

            search.verifyNoInteractions();
            assertThat(capture.events.list).isEmpty();
        }
    }

    @Test
    void semanticInferenceFailureReleasesPredictorWithoutLoggingPrivateInput() throws Exception {
        var predictor = predictor();
        var service = semanticService(predictor);
        when(predictor.predict(QUERY)).thenThrow(
                new TranslateException(FAILURE, new IOException("private-nested-cause")));
        try (var capture = new LogCapture(LocalEmbeddingService.class)) {
            assertThatThrownBy(() -> service.semanticSearch(QUERY, 10))
                    .isInstanceOf(com.taxonomy.search.SearchUnavailableException.class).hasNoCause();

            verify(predictor).close();
            capture.assertSafeError("SEMANTIC_SEARCH_FAILED");
        }
    }

    @Test
    void semanticBackendFailureRemainsExplicitWithoutLoggingPrivateInput() throws Exception {
        var predictor = predictor();
        var service = semanticService(predictor);
        var entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(predictor.predict(QUERY)).thenReturn(new float[384]);
        doReturn("test-model-identity").when(service).embeddingIndexKey();
        try (var capture = new LogCapture(LocalEmbeddingService.class);
             var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(entityManager)).thenThrow(
                    new IllegalStateException(FAILURE));

            assertThatThrownBy(() -> service.semanticSearch(QUERY, 10))
                    .isInstanceOf(com.taxonomy.search.SearchUnavailableException.class).hasNoCause();

            search.verify(() -> Search.session(entityManager));
            verify(predictor).close();
            capture.assertSafeError("SEMANTIC_SEARCH_FAILED");
        }
    }

    @SuppressWarnings("unchecked")
    private static Predictor<String, float[]> predictor() {
        return mock(Predictor.class);
    }

    @SuppressWarnings("unchecked")
    private static LocalEmbeddingService semanticService(Predictor<String, float[]> predictor) {
        var service = spy(new LocalEmbeddingService());
        ZooModel<String, float[]> model = mock(ZooModel.class);
        when(model.newPredictor()).thenReturn(predictor);
        ReflectionTestUtils.setField(service, "model", model);
        ReflectionTestUtils.setField(service, "embeddingEnabled", true);
        return service;
    }

    private static final class LogCapture implements AutoCloseable {
        private final Logger logger;
        private final Level previousLevel;
        private final boolean previousAdditive;
        private final ListAppender<ILoggingEvent> events = new ListAppender<>();

        private LogCapture(Class<?> source) {
            logger = (Logger) LoggerFactory.getLogger(source);
            previousLevel = logger.getLevel();
            previousAdditive = logger.isAdditive();
            logger.setLevel(Level.TRACE);
            logger.setAdditive(false);
            events.start();
            logger.addAppender(events);
        }

        private void assertSafeError(String code) {
            assertSafeEvent(Level.ERROR, code);
        }

        private void assertSafeEvent(Level level, String code) {
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(level);
                assertThat(event.getFormattedMessage())
                        .doesNotContain("private-", "forged-log-entry", "\r", "\n")
                        .contains(code)
                        .hasSizeLessThan(200);
                assertThat(event.getThrowableProxy()).isNull();
                if (event.getArgumentArray() != null) {
                    assertThat(event.getArgumentArray()).allSatisfy(argument ->
                            assertThat(String.valueOf(argument)).doesNotContain("private-", "forged-log-entry"));
                }
            });
        }

        @Override
        public void close() {
            logger.detachAppender(events);
            events.stop();
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }
}
