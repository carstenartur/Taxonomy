package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.relations.RelationSearchDistribution;
import com.taxonomy.analysis.relations.RequirementRelationSearchService;
import com.taxonomy.analysis.service.*;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.*;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.relations.service.RelationProjectionReadService;
import com.taxonomy.relations.service.RelationProjectionReadService.IdentitySnapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationIdentity;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.*;

import static com.taxonomy.analysis.cluster.DurableClusterAnalysisExecutionTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic encoders exercise production admission, preflight and frozen scoring without native model downloads. */
class ClusterOnnxExecutionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALL_ROOTS = Set.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA");
    private static final RepositoryContext REPOSITORY = RepositoryContext.workspace("repository", "workspace", "draft", "alice");
    private static final EmbeddingModelIdentity MODEL = model("a");

    @Test
    void admissionCapturesAllRootsOnceButWorkerLoadsAndScoresOnlyTheRequiredFrozenRoot() {
        var fixture = new Fixture();
        fixture.admit(AnalysisMode.TAXONOMIES_ONLY, "LOCAL_ONNX");

        verify(fixture.catalogue).captureRoots(source(fixture.context), ALL_ROOTS);
        verifyNoMoreInteractions(fixture.catalogue);
        verify(fixture.projections, times(2)).readIdentitySnapshot(REPOSITORY);
        verifyNoMoreInteractions(fixture.projections);
        assertThat(fixture.scoring).containsOnlyKeys(TaxonomyShardRoot.of("CP"));
        assertThat(fixture.targets).isEmpty();
        var saved = fixture.persisted("CP");
        assertThat(saved.embeddings().source()).isEqualTo(source(fixture.context));
        assertThat(saved.embeddings().model()).isEqualTo(MODEL);
        assertThat(saved.embeddings().nodeTexts()).containsExactly(entry("CP", "Frozen CP.\nOutgoing: supports Frozen IP."));
        assertThat(fixture.admitted.provider()).isEqualTo("LOCAL_ONNX");

        fixture.embeddings.texts.clear();
        var task = rootTask(fixture.context);
        var result = fixture.computation(fixture.llm).score(fixture.input(), task, () -> false);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getRawScores()).containsExactly(entry("CP", 20));
        assertThat(result.getTree()).singleElement().satisfies(root -> assertThat(root.getCode()).isEqualTo("CP"));
        assertThat(fixture.embeddings.texts).containsExactly("query: requirement", saved.embeddings().nodeTexts().get("CP"));
        assertThat(fixture.embeddings.frozenCacheStatistics().vectors()).isEqualTo(1);
        verify(fixture.store).shard(fixture.context, task.root());
        verify(fixture.store, never()).shard(eq(fixture.context), argThat(root -> !root.code().equals("CP")));
        verifyNoInteractions(fixture.nodes, fixture.gateways);
        fixture.assertRestored();
    }

    @Test
    void configuredDefaultOnnxAlsoDecoratesAndFreezesItsProvider() {
        var fixture = new Fixture();
        ReflectionTestUtils.setField(fixture.providers, "llmProviderConfig", "LOCAL_ONNX");
        fixture.admit(AnalysisMode.FULL, null);
        assertThat(fixture.admitted.provider()).isEqualTo("LOCAL_ONNX");
        assertThat(fixture.scoring).hasSize(1);
        assertThat(fixture.targets).hasSize(8).allSatisfy((root, json) ->
                assertThat(JSON.readValue(json, RootCatalogueSnapshot.class).embeddings()).isNotNull());
        verify(fixture.catalogue).captureRoots(source(fixture.context), ALL_ROOTS);
        verifyNoMoreInteractions(fixture.catalogue);
    }

    @Test
    void staleMissingFallbackOrMovedProjectionRejectsAdmissionBeforeAnyDurableWrite() {
        var invalid = List.of(
                new IdentitySnapshot(ReadModel.PROJECTION, ReadinessState.STALE, "commit", Set.of()),
                new IdentitySnapshot(ReadModel.LEGACY_FALLBACK, ReadinessState.NOT_BUILT, "commit", Set.of()),
                new IdentitySnapshot(ReadModel.PROJECTION, ReadinessState.READY, "foreign-commit", Set.of()));
        for (var identity : invalid) {
            var fixture = new Fixture();
            when(fixture.projections.readIdentitySnapshot(REPOSITORY)).thenReturn(identity);
            assertThatThrownBy(() -> fixture.admit(AnalysisMode.TAXONOMIES_ONLY, "LOCAL_ONNX"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("exact source commit");
            verify(fixture.store, never()).admit(any(), any(), any(), anyMap(), anyMap());
            verifyNoInteractions(fixture.nodes, fixture.gateways);
        }
        var fixture = new Fixture();
        when(fixture.projections.readIdentitySnapshot(REPOSITORY)).thenReturn(ready(), invalid.getLast());
        assertThatThrownBy(() -> fixture.admit(AnalysisMode.TAXONOMIES_ONLY, "LOCAL_ONNX"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("exact source commit");
        verify(fixture.store, never()).admit(any(), any(), any(), anyMap(), anyMap());
    }

    @Test
    void absentEmbeddingEvidenceOrDifferentModelFailsBeforeScoringAndCleansBindings() {
        for (boolean missingEvidence : List.of(true, false)) {
            var fixture = new Fixture();
            fixture.admit(AnalysisMode.TAXONOMIES_ONLY, "LOCAL_ONNX");
            if (missingEvidence) {
                fixture.scoring.put(TaxonomyShardRoot.of("CP"), JSON.writeValueAsString(root(source(fixture.context), "CP")));
            } else fixture.embeddings.identity = model("c");
            var llm = mock(LlmService.class);
            assertThatThrownBy(() -> fixture.computation(llm).score(fixture.input(), rootTask(fixture.context), () -> false))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("embedding");
            verifyNoInteractions(llm, fixture.nodes, fixture.gateways);
            assertThat(fixture.embeddings.texts).isEmpty();
            fixture.assertRestored();
        }
    }

    @Test
    void workerWithoutFrozenEmbeddingDependencyRejectsOnnxBeforeReadingAnyShard() {
        var fixture = new Fixture();
        fixture.admit(AnalysisMode.TAXONOMIES_ONLY, "LOCAL_ONNX");
        var llm = mock(LlmService.class);
        var computation = new FrozenClusterAnalysisComputation(fixture.store, fixture.snapshots, llm,
                fixture.providers, ClusterOnnxExecutionTest::guard, JSON);
        assertThatThrownBy(() -> computation.score(fixture.input(), rootTask(fixture.context), () -> false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOCAL_ONNX");
        verify(fixture.store, never()).shard(any(), any());
        verifyNoInteractions(llm);
        fixture.assertRestored();
    }

    @Test
    void relationOnlyWorkerKeepsOnnxGenerationExplicitlyUnassessedWithoutInference() {
        var fixture = new Fixture();
        fixture.admit(AnalysisMode.FULL, "LOCAL_ONNX");
        var sourceResults = new AnalysisResult(Map.of(), List.of()); sourceResults.setStatus("SUCCESS");
        when(fixture.store.rootResults(fixture.context)).thenReturn(sourceResults);
        var relations = new RequirementRelationSearchService(fixture.taxonomy, new RelationCompatibilityMatrix(),
                fixture.llm, new AiPromptBudgetPolicy(new AiTargetCatalogService(fixture.providers)));
        var snapshots = new CatalogueSnapshotService(fixture.nodes, fixture.overlay,
                new CatalogueRuntimePolicy("worker", String.join(",", ALL_ROOTS)), mock(CatalogueSourceJournal.class));
        var computation = new FrozenClusterRelationComputation(fixture.store, snapshots, fixture.taxonomy, relations,
                fixture.providers, JSON, ClusterOnnxExecutionTest::guard);
        var task = (RelationAnalysisTask) new AnalysisMessageFactory(fixture.context, Clock.systemUTC()).task(
                AnalysisTaskGraph.relationsOnly(fixture.context.operationId(), TaxonomyShardRoot.DEFAULT_ROOTS).tasks().getFirst());
        var preparation = (ClusterRelationComputation.Preparation) computation.compute(fixture.input(), task, () -> false);
        assertThat(preparation.plan().stopReason()).startsWith("GENERATION_UNSUPPORTED: LOCAL_ONNX");
        assertThat(preparation.plan().sourceCalls()).isZero();
        assertThat(preparation.plan().items()).isEmpty();
        assertThat(RelationSearchDistribution.combine(preparation.plan(), List.of()).isSearchExhausted()).isFalse();
        assertThat(fixture.embeddings.texts).isEmpty();
        verifyNoInteractions(fixture.nodes, fixture.gateways);
        fixture.assertRestored();
    }

    private static IdentitySnapshot ready() {
        return new IdentitySnapshot(ReadModel.PROJECTION, ReadinessState.READY, "commit",
                Set.of(new RelationIdentity("CP", RelationType.SUPPORTS, "IP")));
    }

    private static EmbeddingModelIdentity model(String hashDigit) {
        return new EmbeddingModelIdentity(hashDigit.repeat(64), "b".repeat(64), Map.of(),
                "query: ", EmbeddingModelIdentity.INFERENCE_VERSION);
    }

    private static SubtaxonomyAnalysisTask rootTask(AnalysisOperationContext context) {
        return (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(context, Clock.systemUTC()).task(
                AnalysisTaskGraph.plan(context.operationId(), List.of(TaxonomyShardRoot.of("CP")), false).tasks().getFirst());
    }

    private static AnalysisMemoryGuard guard() {
        return new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 16 * 1024 * 1024, 5000, 1800000),
                () -> new AnalysisMemoryGuard.Sample(1, 1024L * 1024 * 1024), System::currentTimeMillis);
    }

    private static final class Fixture {
        final AnalysisOperationContext context = context("onnx-integration");
        final ClusterAnalysisStore store = mock(ClusterAnalysisStore.class);
        final CatalogueSnapshotService catalogue = mock(CatalogueSnapshotService.class);
        final RelationProjectionReadService projections = mock(RelationProjectionReadService.class);
        final WorkspaceViewContextReadPort views = mock(WorkspaceViewContextReadPort.class);
        final TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
        final LlmGatewayRegistry gateways = mock(LlmGatewayRegistry.class);
        final FrozenEmbeddings embeddings = new FrozenEmbeddings();
        final LlmProviderConfig providers = new LlmProviderConfig(embeddings);
        final CatalogueOverlayService overlay = new CatalogueOverlayService(JSON, new DefaultResourceLoader(), false, "unused");
        final CatalogueSnapshotService snapshots = new CatalogueSnapshotService(nodes, overlay,
                new CatalogueRuntimePolicy("worker", "CP"), mock(CatalogueSourceJournal.class));
        final TaxonomyService taxonomy = new TaxonomyService(nodes, null, new AppInitializationStateService());
        final LlmService llm = new LlmService(providers, gateways, JSON, taxonomy,
                mock(PromptTemplateService.class), embeddings, mock(SavedAnalysisService.class));
        final Map<TaxonomyShardRoot, String> scoring = new LinkedHashMap<>(), targets = new LinkedHashMap<>();
        AnalyzeRequirementCommand admitted;

        Fixture() {
            ReflectionTestUtils.setField(providers, "llmProviderConfig", "OPENAI");
            ReflectionTestUtils.setField(taxonomy, "catalogueOverlayService", overlay);
            ReflectionTestUtils.setField(llm, "catalogueOverlayService", overlay);
            when(views.resolveWorkspaceBranch("alice")).thenReturn("draft");
            when(views.getViewContext(eq("alice"), eq("draft"), any())).thenReturn(
                    new ViewContext("commit", "draft", null, false, false, false));
            when(catalogue.captureRoots(any(), anySet())).thenAnswer(i -> i.<Set<String>>getArgument(1).stream()
                    .sorted().map(code -> root(i.getArgument(0), code)).toList());
            when(projections.readIdentitySnapshot(REPOSITORY)).thenReturn(ready());
            when(store.snapshot(context)).thenReturn(snapshot(context, ClusterAnalysisState.COMPLETED));
            when(store.shard(eq(context), any())).thenAnswer(i -> {
                var root = i.<TaxonomyShardRoot>getArgument(1);
                return scoring.containsKey(root) ? scoring.get(root) : targets.get(root);
            });
            doAnswer(i -> {
                admitted = i.getArgument(1); scoring.putAll(i.getArgument(3)); targets.putAll(i.getArgument(4)); return null;
            }).when(store).admit(eq(context), any(), isNull(), anyMap(), anyMap());
        }

        void admit(AnalysisMode mode, String provider) {
            new DurableClusterAnalysisExecution(store, catalogue, new ClusterAnalysisSignals(), providers, JSON, views,
                    new FrozenCatalogueEmbeddingService(projections, embeddings))
                    .execute(context, command(mode, provider), null, ignored -> { });
        }

        FrozenClusterAnalysisComputation computation(LlmService service) {
            return new FrozenClusterAnalysisComputation(store, snapshots, service, providers,
                    ClusterOnnxExecutionTest::guard, JSON, embeddings);
        }

        RootCatalogueSnapshot persisted(String root) { return JSON.readValue(scoring.get(TaxonomyShardRoot.of(root)), RootCatalogueSnapshot.class); }
        ClusterAnalysisStore.Input input() { return new ClusterAnalysisStore.Input(context, admitted, true, null); }
        void assertRestored() {
            assertThat(FrozenCatalogueContext.current()).isNull();
            assertThat(AnalysisRunControl.active()).isFalse();
            assertThat(providers.getActiveProvider()).isEqualTo(LlmProvider.OPENAI);
            assertThat(providers.isMockMode()).isFalse();
        }
    }

    private static final class FrozenEmbeddings extends LocalEmbeddingService {
        final List<String> texts = new ArrayList<>();
        EmbeddingModelIdentity identity = MODEL;
        FrozenEmbeddings() {
            ReflectionTestUtils.setField(this, "embeddingEnabled", true);
            ReflectionTestUtils.setField(this, "queryPrefix", "query: ");
            ReflectionTestUtils.setField(this, "catalogueRuntimePolicy", new CatalogueRuntimePolicy("worker", "CP"));
        }
        @Override public EmbeddingModelIdentity embeddingIdentity() { return identity; }
        @Override public float[] embed(String text) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(FrozenCatalogueContext.current()).isNotNull();
            texts.add(text);
            float[] vector = new float[384];
            if (text.startsWith("query:")) vector[0] = 1;
            else { vector[0] = .2f; vector[1] = (float) Math.sqrt(.96); }
            return vector;
        }
    }
}
