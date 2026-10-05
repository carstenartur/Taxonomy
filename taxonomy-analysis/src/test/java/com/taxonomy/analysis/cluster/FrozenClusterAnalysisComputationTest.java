package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.AnalysisMode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenClusterAnalysisComputationTest {
    @Test
    void realMockScoringUsesOnlyThePersistedRootAndCleansEveryScope() {
        var mapper = new ObjectMapper();
        var store = mock(ClusterAnalysisStore.class);
        var nodes = mock(TaxonomyNodeRepository.class);
        var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused");
        var snapshots = new CatalogueSnapshotService(nodes, overlay, new CatalogueRuntimePolicy("worker", "CP"), mock(CatalogueSourceJournal.class));
        var taxonomy = new TaxonomyService(nodes, mock(TaxonomyRelationRepository.class), new AppInitializationStateService());
        ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
        var embedding = mock(LocalEmbeddingService.class);
        var providers = new LlmProviderConfig(embedding);
        ReflectionTestUtils.setField(providers, "llmProviderConfig", "OPENAI");
        var gateways = mock(LlmGatewayRegistry.class);
        var llm = new LlmService(providers, gateways, mapper, taxonomy,
                mock(PromptTemplateService.class), embedding, mock(SavedAnalysisService.class));
        ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
        var computation = new FrozenClusterAnalysisComputation(store, snapshots, llm, providers,
                FrozenClusterAnalysisComputationTest::guard, mapper);
        var context = DurableClusterAnalysisExecutionTest.context("score");
        var task = task(context);
        when(store.shard(context, task.root())).thenReturn(mapper.writeValueAsString(
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "CP")));

        var result = computation.score(new ClusterAnalysisStore.Input(context,
                DurableClusterAnalysisExecutionTest.command(AnalysisMode.TAXONOMIES_ONLY, "MOCK"), true, null),
                task, () -> false);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getRawScores()).containsExactly(entry("CP", 58));
        assertThat(result.getTree()).singleElement().satisfies(root -> assertThat(root.getNameEn()).isEqualTo("Frozen CP"));
        assertThat(result.getAnalysisScope().taxonomyRoots()).containsExactly("CP");
        assertThat(result.getProvider()).isEqualTo("Mock");
        assertThat(FrozenCatalogueContext.current()).isNull();
        assertThat(AnalysisRunControl.active()).isFalse();
        assertThat(providers.isMockMode()).isFalse();
        verifyNoInteractions(nodes, gateways, embedding);
        verify(store).shard(context, task.root());
        verifyNoMoreInteractions(store);
    }

    @Test
    void rejectsForeignTaskBeforeReadingAShardAndRejectsOpenTransactions() {
        var store = mock(ClusterAnalysisStore.class);
        var computation = new FrozenClusterAnalysisComputation(store, mock(CatalogueSnapshotService.class),
                mock(LlmService.class), new LlmProviderConfig(mock(LocalEmbeddingService.class)),
                FrozenClusterAnalysisComputationTest::guard, new ObjectMapper());
        var context = DurableClusterAnalysisExecutionTest.context("expected");
        var input = new ClusterAnalysisStore.Input(context,
                DurableClusterAnalysisExecutionTest.command(AnalysisMode.TAXONOMIES_ONLY, "MOCK"), true, null);
        assertThatThrownBy(() -> computation.score(input, task(DurableClusterAnalysisExecutionTest.context("foreign")), () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> computation.score(input, task(context), () -> false))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("transaction");
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(store);
    }

    private static SubtaxonomyAnalysisTask task(AnalysisOperationContext context) {
        return (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(context, Clock.systemUTC())
                .task(AnalysisTaskGraph.plan(context.operationId(), List.of(TaxonomyShardRoot.of("CP")), false).tasks().getFirst());
    }

    private static AnalysisMemoryGuard guard() {
        return new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 16 * 1024 * 1024, 5000, 1800000),
                () -> new AnalysisMemoryGuard.Sample(1, 1024L * 1024 * 1024), System::currentTimeMillis);
    }
}
