package com.taxonomy.search;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.search.config.SpringContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalOnnxIndexInitializerTest {

    @Test
    @SuppressWarnings("unchecked")
    void detailUsesRawStateWithoutReadingEffectiveState() {
        var rawState = (java.util.concurrent.atomic.AtomicReference<LocalOnnxIndexInitializer.State>)
                org.springframework.test.util.ReflectionTestUtils.getField(initializer, "state");
        rawState.set(LocalOnnxIndexInitializer.State.INDEXING_RELATIONS);
        var observed = org.mockito.Mockito.spy(initializer);

        assertThat(observed.getDetail()).doesNotContain("VECTOR_WRITE_FAILED");
        verify(observed, never()).getState();
    }

    @Test
    void initializationFailureDoesNotExposePrivateModelDetailsInStatusOrLogs() throws Exception {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(initializationState.getState()).thenReturn(AppInitializationStateService.State.READY);
        org.mockito.Mockito.doThrow(new IllegalStateException("private-model-directory",
                new java.io.IOException("private-downstream-response")))
                .when(embeddingService).embed(anyString());
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LocalOnnxIndexInitializer.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            initializer.initializeLocalOnnxIndex();
            assertThat(initializer.getState()).isEqualTo(LocalOnnxIndexInitializer.State.FAILED);
            assertThat(initializer.getDetail()).doesNotContain("private-");
            assertThat(events.list).isNotEmpty().allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("private-");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Mock private LocalEmbeddingService embeddingService;
    @Mock private AppInitializationStateService initializationState;
    @Mock private LocalEmbeddingIndexRebuilder indexRebuilder;

    private LocalOnnxIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new LocalOnnxIndexInitializer(
                embeddingService, initializationState, indexRebuilder, new EmbeddingIndexHealth());
    }

    @Test
    void disabledEmbeddingDoesNotStartIndexing() {
        when(embeddingService.isEnabled()).thenReturn(false);

        initializer.initializeLocalOnnxIndex();

        assertThat(initializer.getState())
                .isEqualTo(LocalOnnxIndexInitializer.State.DISABLED);
        assertThat(initializer.hasStarted()).isFalse();
        assertThat(initializer.isNodeSearchReady()).isFalse();
        verifyNoInteractions(initializationState, indexRebuilder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOM_OPENAI", "GEMINI", "LOCAL_ONNX", ""})
    void enabledEmbeddingsBecomeSearchableIndependentlyOfChatProvider(String provider) {
        configureReadyTaxonomyAndSearchableNodes(309);

        new ApplicationContextRunner()
                .withUserConfiguration(LocalOnnxIndexInitializer.class, CatalogueRuntimePolicy.class)
                .withPropertyValues("llm.provider=" + provider)
                .withInitializer(context -> {
                    // Native inference and persistence are test doubles, not managed JPA services.
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("springContextHolder", mock(SpringContextHolder.class));
                    beans.registerSingleton("localEmbeddingService", embeddingService);
                    beans.registerSingleton("initializationStateService", initializationState);
                    beans.registerSingleton("localEmbeddingIndexRebuilder", indexRebuilder);
                    beans.registerSingleton("embeddingIndexHealth", new EmbeddingIndexHealth());
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var configuredInitializer = context.getBean(LocalOnnxIndexInitializer.class);

                    configuredInitializer.initializeLocalOnnxIndex();

                    assertThat(configuredInitializer.getState())
                            .isEqualTo(LocalOnnxIndexInitializer.State.READY);
                    assertThat(configuredInitializer.isNodeSearchReady()).isTrue();
                    assertThat(configuredInitializer.getIndexedNodesAtReadiness()).isEqualTo(309);
                    verify(indexRebuilder).rebuildNodeIndex();
                    verify(indexRebuilder).rebuildRelationIndex();
                });
    }

    @Test
    void successfulNodeAndRelationBuildFinishesReadyInOrder() throws Exception {
        configureReadyTaxonomyAndSearchableNodes(309);

        initializer.initializeLocalOnnxIndex();

        InOrder order = inOrder(embeddingService, indexRebuilder);
        order.verify(embeddingService).embed("Taxonomy embedding index warm-up");
        order.verify(indexRebuilder).rebuildNodeIndex();
        order.verify(embeddingService).indexedNodeCount();
        order.verify(embeddingService).semanticSearch(anyString(), eq(1));
        order.verify(indexRebuilder).rebuildRelationIndex();

        assertThat(initializer.getState())
                .isEqualTo(LocalOnnxIndexInitializer.State.READY);
        assertThat(initializer.isNodeSearchReady()).isTrue();
        assertThat(initializer.getIndexedNodesAtReadiness()).isEqualTo(309);
    }

    @Test
    void relationFailureKeepsNodeSemanticSearchAvailable() throws Exception {
        configureReadyTaxonomyAndSearchableNodes(309);
        org.mockito.Mockito.doThrow(new IllegalStateException("relation failure"))
                .when(indexRebuilder).rebuildRelationIndex();

        initializer.initializeLocalOnnxIndex();

        assertThat(initializer.getState())
                .isEqualTo(LocalOnnxIndexInitializer.State.PARTIAL);
        assertThat(initializer.isNodeSearchReady()).isTrue();
        assertThat(initializer.getDetail()).contains("RELATION_INDEX_FAILED").doesNotContain("relation failure");
    }

    @Test
    void emptyNodeIndexFailsClosedBeforeRelationsStart() throws Exception {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(initializationState.getState())
                .thenReturn(AppInitializationStateService.State.READY);
        when(embeddingService.indexedNodeCount()).thenReturn(0);

        initializer.initializeLocalOnnxIndex();

        assertThat(initializer.getState())
                .isEqualTo(LocalOnnxIndexInitializer.State.FAILED);
        assertThat(initializer.isNodeSearchReady()).isFalse();
        assertThat(initializer.getDetail())
                .contains("INDEXING_NODES", "EMBEDDING_INDEX_FAILED");
        verify(indexRebuilder, never()).rebuildRelationIndex();
    }

    @Test
    void missingNodeVectorFailsClosedBeforeRelationsStart() throws Exception {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(initializationState.getState())
                .thenReturn(AppInitializationStateService.State.READY);
        when(embeddingService.indexedNodeCount()).thenReturn(309);
        when(embeddingService.semanticSearch(anyString(), eq(1)))
                .thenReturn(List.of());

        initializer.initializeLocalOnnxIndex();

        assertThat(initializer.getState())
                .isEqualTo(LocalOnnxIndexInitializer.State.FAILED);
        assertThat(initializer.isNodeSearchReady()).isFalse();
        assertThat(initializer.getDetail())
                .contains("INDEXING_NODES", "EMBEDDING_INDEX_FAILED");
        verify(indexRebuilder, never()).rebuildRelationIndex();
    }

    @Test
    void taxonomyFailurePreventsModelAndIndexSideEffects() {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(initializationState.getState())
                .thenReturn(AppInitializationStateService.State.FAILED);

        initializer.initializeLocalOnnxIndex();

        assertThat(initializer.getState())
                .isEqualTo(LocalOnnxIndexInitializer.State.FAILED);
        assertThat(initializer.isNodeSearchReady()).isFalse();
        verifyNoInteractions(indexRebuilder);
    }

    @Test
    void repeatedReadyEventDoesNotLaunchCompetingIndexBuilds() throws Exception {
        configureReadyTaxonomyAndSearchableNodes(309);

        initializer.initializeLocalOnnxIndex();
        initializer.initializeLocalOnnxIndex();

        verify(indexRebuilder).rebuildNodeIndex();
        verify(indexRebuilder).rebuildRelationIndex();
    }

    private void configureReadyTaxonomyAndSearchableNodes(int count) {
        when(embeddingService.isEnabled()).thenReturn(true);
        when(initializationState.getState())
                .thenReturn(AppInitializationStateService.State.READY);
        when(embeddingService.indexedNodeCount()).thenReturn(count);
        TaxonomyNodeDto hit = new TaxonomyNodeDto();
        hit.setCode("BP");
        when(embeddingService.semanticSearch(anyString(), eq(1)))
                .thenReturn(List.of(hit));
    }
}
