package com.taxonomy.search;

import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.shared.service.AppInitializationStateService;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalOnnxIndexInitializerTest {

    @Mock private LocalEmbeddingService embeddingService;
    @Mock private AppInitializationStateService initializationState;
    @Mock private LocalEmbeddingIndexRebuilder indexRebuilder;

    private LocalOnnxIndexInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new LocalOnnxIndexInitializer(
                embeddingService, initializationState, indexRebuilder);
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

    @Test
    void nodeIndexBecomesReadyBeforeRelationIndexCompletes() throws Exception {
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
        assertThat(initializer.getDetail()).contains("relation failure");
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
                .contains("without searchable taxonomy documents");
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
                .contains("without a searchable embedding vector");
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
