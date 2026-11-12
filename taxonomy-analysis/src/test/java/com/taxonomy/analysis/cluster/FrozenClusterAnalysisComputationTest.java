package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.AnalysisCoverage;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisScope;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
        var liveSettings = mock(AnalysisRuntimeSettings.class);
        when(liveSettings.getString(anyString(), anyString())).thenReturn("short");
        ReflectionTestUtils.setField(llm, "analysisRuntimeSettings", liveSettings);
        var computation = new FrozenClusterAnalysisComputation(store, snapshots, llm, providers,
                FrozenClusterAnalysisComputationTest::guard, mapper);
        var context = DurableClusterAnalysisExecutionTest.context("score");
        var task = task(context);
        when(store.shard(context, task.root())).thenReturn(mapper.writeValueAsString(
                DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), "CP")));

        var result = computation.score(new ClusterAnalysisStore.Input(context,
                DurableClusterAnalysisExecutionTest.command(AnalysisMode.TAXONOMIES_ONLY, "MOCK")
                        .withAnalysisReasonLength(com.taxonomy.dto.AnalysisReasonLength.DETAILED)
                        .withAnalysisLanguage(com.taxonomy.dto.AnalysisLanguage.GERMAN), true, null),
                task, () -> false);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getRawScores()).containsExactly(entry("CP", 58d));
        assertThat(result.getTree()).singleElement().satisfies(root -> assertThat(root.getNameEn()).isEqualTo("Frozen CP"));
        assertThat(result.getAnalysisScope().taxonomyRoots()).containsExactly("CP");
        assertThat(result.getProvider()).isEqualTo("Mock");
        assertThat(result.getAnalysisReasonLength()).isEqualTo(com.taxonomy.dto.AnalysisReasonLength.DETAILED);
        assertThat(result.getAnalysisLanguage()).isEqualTo(com.taxonomy.dto.AnalysisLanguage.GERMAN);
        assertThat(AnalysisLanguageContext.current()).isNull();
        assertThat(AnalysisReasonContext.current()).isNull();
        verifyNoInteractions(liveSettings);
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
            var computation = mockProviderWorker(db.store, mapper, "CP", com.taxonomy.dsl.model.CatalogueDefinition.standard());
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
    void timedOutCategoryPreservesThePreviouslyCompletedProductGap() {
        try (var db = new ClusterAnalysisStoreTest.Database()) {
            var mapper = new ObjectMapper();
            var context = ClusterAnalysisStoreTest.context("worker-product-gap");
            var source = DurableClusterAnalysisExecutionTest.source(context);
            var ip = TaxonomyShardRoot.of("IP");
            var root = DurableClusterAnalysisExecutionTest.root(source, ip.code());
            var nodes = new ArrayList<>(root.nodes());
            nodes.add(node("IP-A", "IP", 1, "PRODUCT_FAMILY"));
            nodes.add(node("IP-P", "IP-A", 2, "PRODUCT"));
            nodes.add(node("IP-Z", "IP", 1, "CATEGORY"));
            nodes.add(node("IP-C", "IP-Z", 2, "CATEGORY"));
            var frozen = new RootCatalogueSnapshot(root.schemaVersion(), source, ip.code(), nodes,
                    root.overlayMetadata(), root.catalogueProvenance());
            var original = ClusterAnalysisStoreTest.command("requirement");
            var command = new AnalyzeRequirementCommand(original.businessText(), false, 20, "CUSTOM_OPENAI",
                    original.username(), original.workspaceContext(), null,
                    new AnalysisScope(Set.of(ip.code()), AnalysisMode.TAXONOMIES_ONLY));
            db.store.admit(context, command, null, Map.of(ip, mapper.writeValueAsString(frozen)));

            var catalogueNodes = mock(TaxonomyNodeRepository.class);
            var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused");
            var snapshots = new CatalogueSnapshotService(catalogueNodes, overlay, new CatalogueRuntimePolicy("worker", "IP"), mock(CatalogueSourceJournal.class));
            var taxonomy = new TaxonomyService(catalogueNodes, mock(TaxonomyRelationRepository.class), new AppInitializationStateService());
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
            var embedding = mock(LocalEmbeddingService.class);
            var providers = new LlmProviderConfig(embedding);
            String url = "https://worker.invalid/chat/completions";
            ReflectionTestUtils.setField(providers, "customLlmUrl", url);
            ReflectionTestUtils.setField(providers, "customLlmModel", "fixture");
            var http = new RestTemplate();
            var server = MockRestServiceServer.bindTo(http).build();
            for (String reply : List.of("{\"IP\":100}", "{\"IP-A\":50,\"IP-Z\":50}",
                    "{\"IP-P\":{\"score\":20,\"reason\":\"below threshold\"}}")) {
                String body = mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", reply)))));
                server.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            }
            var clock = new AtomicLong();
            server.expect(requestTo(url)).andRespond(request -> {
                clock.set(10);
                return withServerError().createResponse(request);
            });
            AnalysisRuntimeSettings settings = (key, fallback) -> "llm.retry.max".equals(key) ? 0 : fallback;
            var gateway = new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, url, "fixture", 0, http, mapper,
                    new LlmResponseParser(mapper), settings, null, null);
            var gateways = mock(LlmGatewayRegistry.class);
            when(gateways.getGatewayById(LlmProvider.CUSTOM_OPENAI.id())).thenReturn(gateway);
            var templates = mock(PromptTemplateService.class);
            when(templates.renderRootPrompt(anyString(), anyString(), anyString())).thenReturn("ROOT");
            when(templates.renderPrompt(anyString(), anyString(), anyString(), anyDouble(), anyString())).thenReturn("CATEGORIES");
            when(templates.renderProductPrompt(anyString(), anyString(), anyString(), anyInt())).thenReturn("PRODUCTS");
            var llm = new LlmService(providers, gateways, mapper, taxonomy, templates, embedding, mock(SavedAnalysisService.class));
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
            var computation = new FrozenClusterAnalysisComputation(db.store, snapshots, llm, providers,
                    () -> new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 16 * 1024 * 1024, 5000, 10),
                            () -> new AnalysisMemoryGuard.Sample(1, 1024L * 1024 * 1024), clock::get), mapper);
            var worker = new ClusterAnalysisService(db.store, computation, new ClusterAnalysisSignals());

            var completion = (SubtaxonomyAnalysisCompleted) db.completions.commit(worker.prepare(db.task(ip)));
            assertThat(completion.outcome()).isEqualTo(AnalysisTaskOutcome.STOPPED);
            assertThat(completion.stopReason()).isEqualTo("TIME_LIMIT");
            assertThat(completion.scoredNodes()).isEqualTo(4);
            assertThat(db.store.accept(completion)).isTrue();
            server.verify();

            var result = db.store.snapshot(context).result();
            assertThat(result.getStatus()).isEqualTo("PARTIAL");
            assertThat(result.getWarnings()).filteredOn("TIME_LIMIT"::equals).containsExactly("TIME_LIMIT");
            assertThat(result.getWarnings()).anyMatch(warning -> warning.startsWith("TIME_LIMIT: analysis stopped"));
            assertThat(result.getProductCoverageGaps()).singleElement().satisfies(gap -> {
                assertThat(gap.productFamilyCode()).isEqualTo("IP-A");
                assertThat(gap.candidateCodes()).containsExactly("IP-P");
            });
            assertThat(result.getRawScores()).containsEntry("IP-P", 0d).doesNotContainKey("IP-C");
            assertThat(result.getAnalysisCoverage().nodes().get("IP-C").state()).isEqualTo(AnalysisCoverage.State.UNKNOWN);
        }
    }

    private static RootCatalogueSnapshot.Node node(String code, String parent, int level, String role) {
        var node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        node.setTaxonomyRoot("IP");
        node.setParentCode(parent);
        node.setLevel(level);
        return RootCatalogueSnapshot.Node.capture(node,
                new CatalogueOverlayService.NodeMetadata(role, List.of(), 1, false, null), false);
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

    static FrozenClusterAnalysisComputation mockProviderWorker(ClusterAnalysisStore store, ObjectMapper mapper,
            String rootCode, com.taxonomy.dsl.model.CatalogueDefinition catalogue) {
        var nodes = mock(TaxonomyNodeRepository.class);
        var overlay = new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused");
        var snapshots = new CatalogueSnapshotService(nodes, overlay, new CatalogueRuntimePolicy("worker", rootCode, catalogue), mock(CatalogueSourceJournal.class));
        var taxonomy = new TaxonomyService(nodes, mock(TaxonomyRelationRepository.class), new AppInitializationStateService());
        ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
        var embedding = mock(LocalEmbeddingService.class);
        var providers = new LlmProviderConfig(embedding);
        var llm = new LlmService(providers, mock(LlmGatewayRegistry.class), mapper, taxonomy,
                mock(PromptTemplateService.class), embedding, mock(SavedAnalysisService.class));
        ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
        return new FrozenClusterAnalysisComputation(store, snapshots, llm, providers,
                FrozenClusterAnalysisComputationTest::guard, mapper);
    }

    private static AnalysisMemoryGuard guard() {
        return new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 16 * 1024 * 1024, 5000, 1800000),
                () -> new AnalysisMemoryGuard.Sample(1, 1024L * 1024 * 1024), System::currentTimeMillis);
    }
}
