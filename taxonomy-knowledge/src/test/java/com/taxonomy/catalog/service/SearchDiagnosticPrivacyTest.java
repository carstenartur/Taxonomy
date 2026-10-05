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
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchDiagnosticPrivacyTest {

    private static final String QUERY = "private-business-query\r\nforged-log-entry";
    private static final String FAILURE = "private-backend-response-token";

    @Test
    void fullTextBackendFailureRetainsFallbackWithoutLoggingQueryOrFailurePayload() {
        var service = new SearchService();
        var entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        try (var capture = new LogCapture(SearchService.class);
             var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(entityManager)).thenThrow(
                    new IllegalStateException(FAILURE, new IOException("private-nested-cause")));

            assertThat(service.search(QUERY, 10)).isEmpty();

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
            assertThat(service.semanticSearch(QUERY, 10)).isEmpty();

            verify(predictor).close();
            capture.assertSafeError("SEMANTIC_SEARCH_FAILED");
        }
    }

    @Test
    void semanticBackendFailureRetainsFallbackWithoutLoggingPrivateInput() throws Exception {
        var predictor = predictor();
        var service = semanticService(predictor);
        var entityManager = mock(EntityManager.class);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(predictor.predict(QUERY)).thenReturn(new float[384]);
        try (var capture = new LogCapture(LocalEmbeddingService.class);
             var search = mockStatic(Search.class)) {
            search.when(() -> Search.session(entityManager)).thenThrow(
                    new IllegalStateException(FAILURE));

            assertThat(service.semanticSearch(QUERY, 10)).isEmpty();

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
        var service = new LocalEmbeddingService();
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
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
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
