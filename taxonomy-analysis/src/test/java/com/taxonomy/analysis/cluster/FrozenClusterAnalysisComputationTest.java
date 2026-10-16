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
        var diagnostics = mock(ClusterAnalysisDiagnostics.Session.class);
        when(store.diagnostics(any(), anyInt())).thenReturn(diagnostics);
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
        assertThat(result.getRawScores()).containsExactly(entry("CP", 58d));
        assertThat(result.getTree()).singleElement().satisfies(root -> assertThat(root.getNameEn()).isEqualTo("Frozen CP"));
        assertThat(result.getAnalysisScope().taxonomyRoots()).containsExactly("CP");
        assertThat(result.getProvider()).isEqualTo("Mock");
        assertThat(FrozenCatalogueContext.current()).isNull();
        assertThat(AnalysisRunControl.active()).isFalse();
        assertThat(providers.isMockMode()).isFalse();
        verifyNoInteractions(nodes, gateways, embedding);
        verify(store).shard(context, task.root());
        verify(store).diagnostics(task, 0);
        verifyNoMoreInteractions(store);
    }

    @Test
    void realFrozenWorkerKeepsLogicalCallDiagnosticsAfterItsScopeCloses() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var mapper = new ObjectMapper();
            var context = ClusterAnalysisStoreTest.context("worker-diagnostics");
            db.admitFrozen(context);
            var task = db.task(TaxonomyShardRoot.of("CP"));
            var input = db.store.start(task);
            var nodes = mock(TaxonomyNodeRepository.class);
            var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused");
            var snapshots = new CatalogueSnapshotService(nodes, overlay, new CatalogueRuntimePolicy("worker", "CP"), mock(CatalogueSourceJournal.class));
            var taxonomy = new TaxonomyService(nodes, mock(TaxonomyRelationRepository.class), new AppInitializationStateService());
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
            var embedding = mock(LocalEmbeddingService.class);
            var providers = new LlmProviderConfig(embedding);
            var llm = new LlmService(providers, mock(LlmGatewayRegistry.class), mapper, taxonomy,
                    mock(PromptTemplateService.class), embedding, mock(SavedAnalysisService.class));
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
            var computation = new FrozenClusterAnalysisComputation(db.store, snapshots, llm, providers,
                    FrozenClusterAnalysisComputationTest::guard, mapper);
            assertThat(computation.score(input,
                    task, () -> false).getStatus()).isEqualTo("SUCCESS");
            try (var observation = new DurableClusterAnalysisObservation(db.store, new ClusterAnalysisSignals(), AnalysisEventPublisher.NONE)) {
                var view = observation.snapshot(context.operationId(), "alice", ClusterAnalysisStoreTest.command("requirement").workspaceContext()).orElseThrow();
                assertThat(view.snapshot().calls()).hasSize(1);
                assertThat(view.snapshot().calls().getFirst().status()).isEqualTo("COMPLETED");
                assertThat(view.snapshot().calls().getFirst().provider()).isEqualTo("Mock");
            }
            assertThat(AnalysisRunControl.active()).isFalse();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void realFrozenWorkerBindsThePhysicalJournalBeforeDeterministicHttp(boolean cancelAfterResponse) {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var mapper = new ObjectMapper(); var context = ClusterAnalysisStoreTest.context("worker-http");
            var original = ClusterAnalysisStoreTest.command("requirement");
            var command = new com.taxonomy.analysis.usecase.AnalyzeRequirementCommand(original.businessText(), false, 20, "CUSTOM_OPENAI", original.username(), original.workspaceContext(), null, original.analysisScope());
            var source = DurableClusterAnalysisExecutionTest.source(context);
            db.store.admit(context, command, null, java.util.Map.of(TaxonomyShardRoot.of("CP"), mapper.writeValueAsString(DurableClusterAnalysisExecutionTest.root(source, "CP")),
                    TaxonomyShardRoot.of("IP"), mapper.writeValueAsString(DurableClusterAnalysisExecutionTest.root(source, "IP"))));
            var task = db.task(TaxonomyShardRoot.of("CP")); var input = db.store.start(task);
            var nodes = mock(TaxonomyNodeRepository.class); var embedding = mock(LocalEmbeddingService.class);
            var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused");
            var snapshots = new CatalogueSnapshotService(nodes, overlay, new CatalogueRuntimePolicy("worker", "CP"), mock(CatalogueSourceJournal.class));
            var taxonomy = new TaxonomyService(nodes, mock(TaxonomyRelationRepository.class), new AppInitializationStateService());
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
            var providers = new LlmProviderConfig(embedding);
            String url = "https://worker.invalid/chat/completions";
            ReflectionTestUtils.setField(providers, "customLlmUrl", url); ReflectionTestUtils.setField(providers, "customLlmModel", "fixture");
            var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            var http = new org.springframework.web.client.RestTemplate(); var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(http).build();
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(url)).andRespond(request -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(db.store.callDiagnostics().detail(context, 1).attempts()).singleElement().satisfies(a -> assertThat(a.state()).isEqualTo("STARTED"));
                if (cancelAfterResponse) { db.store.cancel(context); cancelled.set(true); }
                String response = mapper.writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of("message", java.util.Map.of("content", "{\"CP\":75}"))),
                        "usage", java.util.Map.of("prompt_tokens", 5, "completion_tokens", 2, "total_tokens", 7)));
                return org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response, org.springframework.http.MediaType.APPLICATION_JSON).createResponse(request);
            });
            AnalysisRuntimeSettings settings = (key, fallback) -> "llm.retry.max".equals(key) ? 0 : fallback;
            var gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, url, "fixture", 0, http, mapper, new LlmResponseParser(mapper), settings, null, null);
            var gateways = mock(LlmGatewayRegistry.class); when(gateways.getGatewayById(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(gateway);
            var templates = mock(PromptTemplateService.class); when(templates.renderPrompt(anyString(), anyString(), anyString(), anyDouble(), anyString())).thenReturn("PRIVATE-FROZEN-REQUEST");
            var llm = new LlmService(providers, gateways, mapper, taxonomy, templates, embedding, mock(SavedAnalysisService.class));
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
            var computation = new FrozenClusterAnalysisComputation(db.store, snapshots, llm, providers, FrozenClusterAnalysisComputationTest::guard, mapper);
            if (cancelAfterResponse) {
                assertThatThrownBy(() -> computation.score(input, task, cancelled::get)).isInstanceOf(AnalysisStoppedException.class);
                assertThat(db.store.snapshot(context).state()).isEqualTo(ClusterAnalysisState.CANCELLED);
                assertThat(db.store.callDiagnostics().summaries(context).calls()).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("STOPPED"));
                assertThat(db.store.callDiagnostics().detail(context, 1).response()).contains("CP");
            } else assertThat(computation.score(input, task, cancelled::get).getRawScores()).containsEntry("CP", 75d);
            server.verify();
            assertThat(db.store.callDiagnostics().detail(context, 1).attempts()).singleElement().satisfies(a -> {
                assertThat(a.source()).isEqualTo("HTTP"); assertThat(a.usage().totalTokens()).isEqualTo(7L);
            });
            assertThat(AnalysisRunControl.active()).isFalse(); verifyNoInteractions(nodes);
        }
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
