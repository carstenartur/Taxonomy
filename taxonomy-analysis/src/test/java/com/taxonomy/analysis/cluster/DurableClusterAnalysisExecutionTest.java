package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableClusterAnalysisExecutionTest {
    private final ClusterAnalysisStore store = mock(ClusterAnalysisStore.class);
    private final CatalogueSnapshotService catalogue = mock(CatalogueSnapshotService.class);
    private final ClusterAnalysisSignals signals = new ClusterAnalysisSignals();
    private final LlmProviderConfig providers = new LlmProviderConfig(mock(LocalEmbeddingService.class));
    private final WorkspaceViewContextReadPort views = mock(WorkspaceViewContextReadPort.class);
    private final DurableClusterAnalysisExecution execution = new DurableClusterAnalysisExecution(
            store, catalogue, signals, providers, new ObjectMapper(), views);

    @BeforeEach void currentSource() {
        when(views.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(views.getViewContext(eq("alice"), eq("draft"), any())).thenReturn(
                new ViewContext("commit", "draft", null, false, false, false));
    }

    @Test
    void freezesDefaultProviderAndOnlySelectedScoringRootsBeforeAdmission() {
        ReflectionTestUtils.setField(providers, "llmProviderConfig", "OPENAI");
        var command = command(AnalysisMode.TAXONOMIES_ONLY, null);
        var context = context("selected");
        when(catalogue.captureRoots(any(), anySet())).thenAnswer(i -> i.<Set<String>>getArgument(1).stream()
                .sorted().map(code -> root(i.getArgument(0), code)).toList());
        when(store.snapshot(context)).thenReturn(snapshot(context, ClusterAnalysisState.COMPLETED));
        doAnswer(i -> {
            assertThat(signals.listenerCount()).isEqualTo(1);
            AnalyzeRequirementCommand frozen = i.getArgument(1);
            assertThat(frozen.provider()).isEqualTo("OPENAI");
            assertThat(((java.util.Map<?, ?>) i.getArgument(3)).keySet()).isEqualTo(Set.of(TaxonomyShardRoot.of("CP")));
            assertThat((java.util.Map<?, ?>) i.getArgument(4)).isEmpty();
            return null;
        }).when(store).admit(eq(context), any(), isNull(), anyMap(), anyMap());
        assertThat(execution.execute(context, command, null, ignored -> { }).getStatus()).isEqualTo("SUCCESS");
        verify(catalogue).captureRoots(source(context), Set.of("CP"));
        verifyNoMoreInteractions(catalogue);
        assertThat(signals.listenerCount()).isZero();
    }

    @Test
    void fullModeFreezesAllRelationTargetsButScoresOnlySelectedRoots() {
        when(catalogue.captureRoots(any(), anySet())).thenAnswer(i -> i.<Set<String>>getArgument(1).stream()
                .sorted().map(code -> root(i.getArgument(0), code)).toList());
        var context = context("relations");
        when(store.snapshot(context)).thenReturn(snapshot(context, ClusterAnalysisState.COMPLETED));
        doAnswer(i -> {
            assertThat((java.util.Map<?, ?>) i.getArgument(3)).hasSize(1);
            assertThat((java.util.Map<?, ?>) i.getArgument(4)).hasSize(8);
            assertThat(((AnalyzeRequirementCommand) i.getArgument(1)).provider()).isEqualTo("MOCK");
            return null;
        }).when(store).admit(eq(context), any(), isNull(), anyMap(), anyMap());
        execution.execute(context, command(AnalysisMode.FULL, "MOCK"), null, ignored -> { });
        verify(catalogue).captureRoots(source(context), Set.of("BP", "BR", "CP", "CI", "CO", "CR", "IP", "UA"));
        verifyNoMoreInteractions(catalogue);
    }

    @Test
    void readsInitialStateThenOnlyWakesOnMatchingEventsOrReconnect() throws Exception {
        var context = context("waiting");
        when(catalogue.captureRoots(any(), anySet())).thenAnswer(i -> i.<Set<String>>getArgument(1).stream()
                .sorted().map(code -> root(i.getArgument(0), code)).toList());
        var firstRead = new CountDownLatch(1);
        var reads = new AtomicInteger();
        when(store.snapshot(context)).thenAnswer(i -> {
            if (reads.incrementAndGet() == 1) { firstRead.countDown(); return snapshot(context, ClusterAnalysisState.RUNNING); }
            return snapshot(context, ClusterAnalysisState.COMPLETED);
        });
        try (var workers = Executors.newSingleThreadExecutor()) {
            var result = workers.submit(() -> execution.execute(context, command(AnalysisMode.TAXONOMIES_ONLY, "MOCK"),
                    null, ignored -> { }));
            assertThat(firstRead.await(5, TimeUnit.SECONDS)).isTrue();
            signals.progress(new AnalysisMessageFactory(context("foreign"), Clock.systemUTC())
                    .progress(1, AnalysisProgressPhase.OPERATION_COMPLETED, null, 1, 1));
            assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(reads.get()).isEqualTo(1);
            signals.reconnected();
            assertThat(result.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("SUCCESS");
            assertThat(reads.get()).isEqualTo(2);
        }
    }

    @Test
    void observerFailureDetachesWithoutCancellingTheDurableRun() {
        var context = context("detached");
        when(catalogue.captureRoots(any(), anySet())).thenAnswer(i -> i.<Set<String>>getArgument(1).stream()
                .sorted().map(code -> root(i.getArgument(0), code)).toList());
        when(store.snapshot(context)).thenReturn(snapshot(context, ClusterAnalysisState.RUNNING));
        assertThatThrownBy(() -> execution.execute(context, command(AnalysisMode.TAXONOMIES_ONLY, "MOCK"), null,
                ignored -> { throw new ClusterAnalysisObservationDetachedException(); }))
                .isInstanceOf(ClusterAnalysisObservationDetachedException.class);
        verify(store).admit(eq(context), any(), isNull(), anyMap(), anyMap());
        verify(store, never()).cancel(any());
        assertThat(signals.listenerCount()).isZero();
    }

    @Test
    void rejectsLocalOnnxBeforeAnyCatalogueCaptureOrAdmission() {
        assertThatThrownBy(() -> execution.execute(context("onnx"), command(AnalysisMode.TAXONOMIES_ONLY, "LOCAL_ONNX"),
                null, ignored -> { })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOCAL_ONNX");
        verifyNoInteractions(store, catalogue);
    }

    @Test
    void movingSourceDuringCaptureRejectsBeforeAdmissionAndClosesListener() {
        var context = context("moved");
        when(catalogue.captureRoots(any(), anySet())).thenAnswer(i -> {
            when(views.getViewContext(eq("alice"), eq("draft"), any())).thenReturn(
                    new ViewContext("new-commit", "draft", null, false, false, false));
            return i.<Set<String>>getArgument(1).stream().sorted().map(code -> root(i.getArgument(0), code)).toList();
        });
        assertThatThrownBy(() -> execution.execute(context, command(AnalysisMode.TAXONOMIES_ONLY, "MOCK"),
                new ViewContext("commit", "draft", null, false, false, false), ignored -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("source changed");
        verifyNoInteractions(store);
        assertThat(signals.listenerCount()).isZero();
    }

    @Test
    void architectureOnlyStillFreezesAllRootsWithoutAddingScoringTasks() {
        when(catalogue.captureRoots(any(), anySet())).thenAnswer(i -> i.<Set<String>>getArgument(1).stream()
                .sorted().map(code -> root(i.getArgument(0), code)).toList());
        var context = context("architecture");
        var c = command(AnalysisMode.TAXONOMIES_ONLY, "MOCK");
        var withArchitecture = new AnalyzeRequirementCommand(c.businessText(), true, c.maxArchitectureNodes(),
                c.provider(), c.username(), c.workspaceContext(), c.provenance(), c.analysisScope());
        when(store.snapshot(context)).thenReturn(snapshot(context, ClusterAnalysisState.COMPLETED));
        execution.execute(context, withArchitecture, null, ignored -> { });
        verify(store).admit(eq(context), any(), isNull(), argThat(m -> m.size() == 1), argThat(m -> m.size() == 8));
    }

    static AnalyzeRequirementCommand command(AnalysisMode mode, String provider) {
        return new AnalyzeRequirementCommand("requirement", false, 20, provider, "alice",
                new WorkspaceContext("alice", "workspace", "draft", "repository"), null,
                new AnalysisScope(Set.of("CP"), mode));
    }

    static AnalysisOperationContext context(String id) {
        return new AnalysisOperationContext(id, new AnalysisSourceAuthority("repository", "workspace", "draft", "commit"),
                RequirementReference.adHoc("requirement"), id);
    }

    static CatalogueSourceIdentity source(AnalysisOperationContext context) {
        var source = context.authority();
        return new CatalogueSourceIdentity(source.repositoryId(), source.workspaceId(), source.branch(), source.sourceCommit());
    }

    static RootCatalogueSnapshot root(CatalogueSourceIdentity source, String root) {
        var node = new TaxonomyNode(); node.setCode(root); node.setTaxonomyRoot(root); node.setNameEn("Frozen " + root);
        return new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, root, List.of(RootCatalogueSnapshot.Node.capture(node,
                new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false)),
                new CatalogueOverlayService.OverlayMetadata(false, "fixture", null, null, null, 0), provenance());
    }

    static CatalogueSourceJournal.Snapshot provenance() {
        var unknown = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        var disabled = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_USED, null, 0);
        return new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                unknown, disabled, unknown);
    }

    static ClusterAnalysisStore.Snapshot snapshot(AnalysisOperationContext context, ClusterAnalysisState state) {
        var result = new AnalysisResult(java.util.Map.of("CP", 55), List.of()); result.setStatus("SUCCESS");
        return new ClusterAnalysisStore.Snapshot(context.operationId(), state, state.terminal() ? 2 : 1,
                state.terminal() ? 1 : 0, 1, state.terminal() ? 0 : 1, 0, 1, 2, List.of(), state.terminal() ? result : null);
    }
}
