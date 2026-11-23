package com.taxonomy.shared.controller;

import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.AppInitializationStateService.State;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.catalog.controller.ApiController;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.search.LocalOnnxIndexInitializer;
import com.taxonomy.versioning.service.CommitIndexSearchLifecycle;
import com.taxonomy.shared.service.HealthSummaryService;
import com.taxonomy.shared.service.LogRingBufferService;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupStatusTest {
    @Test
    void exposesMeasuredCountsAndFeatureReadinessWithoutReadingTheCatalogue() {
        var state = new AppInitializationStateService();
        state.updateProgress(State.BUILDING_INDEX, "INDEXING_NODES", "Indexing taxonomy nodes", 12, 40);
        var taxonomy = mock(TaxonomyService.class);
        when(taxonomy.getStateService()).thenReturn(state);
        var controller = new OperationsApiController(taxonomy, mock(LogRingBufferService.class), mock(HealthSummaryService.class));
        var response = controller.startupStatus();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).containsEntry("initialized", false).containsEntry("phase", "BUILDING_INDEX");
        var progress = (Map<?, ?>) response.getBody().get("progress");
        assertThat(progress.get("phase")).isEqualTo("INDEXING_NODES");
        assertThat(progress.get("processed")).isEqualTo(12L);
        assertThat(progress.get("total")).isEqualTo(40L);
    }

    @Test
    void failureReturnsASafeCodeAndNeverTheUnderlyingException() {
        var state = new AppInitializationStateService();
        state.fail("Catalogue preparation failed", new IllegalStateException("jdbc-password=secret; private/path"));
        var taxonomy = mock(TaxonomyService.class);
        when(taxonomy.getStateService()).thenReturn(state);
        var controller = new OperationsApiController(taxonomy, mock(LogRingBufferService.class), mock(HealthSummaryService.class));
        var response = controller.startupStatus().getBody();
        assertThat(response).containsEntry("initialized", false).containsEntry("errorCode", "INITIALIZATION_FAILED");
        assertThat(response.toString()).doesNotContain("jdbc-password", "private/path");
    }

    @Test
    void catalogueReadinessDoesNotClaimThatBackgroundSearchRecoveryHasFinished() {
        var state = new AppInitializationStateService();
        state.update(State.READY, "Catalogue ready");
        var taxonomy = mock(TaxonomyService.class);
        when(taxonomy.getStateService()).thenReturn(state);
        var embedding = mock(LocalOnnxIndexInitializer.class);
        when(embedding.getProgress()).thenReturn(new LocalOnnxIndexInitializer.ProgressSnapshot(
                LocalOnnxIndexInitializer.State.INDEXING_RELATIONS, "Indexing relationships", 3, 10));
        when(embedding.isNodeSearchReady()).thenReturn(true);
        var history = mock(CommitIndexSearchLifecycle.class);
        when(history.getState()).thenReturn(CommitIndexSearchLifecycle.State.CHECKING);
        var controller = new OperationsApiController(taxonomy, mock(LogRingBufferService.class), mock(HealthSummaryService.class));
        ReflectionTestUtils.setField(controller, "embeddingInitializer", embedding);
        ReflectionTestUtils.setField(controller, "historyInitializer", history);
        var response = controller.startupStatus().getBody();
        assertThat(response).containsEntry("initialized", true).containsEntry("status", "ready");
        var features = (Map<?, ?>) response.get("features");
        assertThat(features.get("catalogue")).isEqualTo(true);
        assertThat(features.get("fullTextSearch")).isEqualTo(true);
        assertThat(features.get("semanticSearch")).isEqualTo(true);
        assertThat(features.get("historySearch")).isEqualTo(false);
        assertThat(((Map<?, ?>) response.get("embedding")).get("processed")).isEqualTo(3L);
        assertThat(((Map<?, ?>) response.get("history")).get("state")).isEqualTo("CHECKING");
    }

    @Test
    void catalogueApiRemainsUnavailableUntilPreparationHasSucceeded() {
        for (State phase : new State[] {State.STARTING, State.LOADING_TAXONOMY, State.BUILDING_INDEX, State.FAILED}) {
            var state = new AppInitializationStateService();
            state.update(phase, "Pending or failed catalogue");
            var taxonomy = new TaxonomyService(mock(TaxonomyNodeRepository.class), mock(TaxonomyRelationRepository.class), state);
            var response = new ApiController(taxonomy, new StaticMessageSource()).getTaxonomy();
            assertThat(response.getStatusCode().value()).as("feature guard in %s", phase).isEqualTo(503);
        }
    }
}
