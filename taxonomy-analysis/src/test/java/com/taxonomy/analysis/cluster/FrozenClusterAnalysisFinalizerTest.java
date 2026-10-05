package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.snapshot.*;
import com.taxonomy.dto.*;
import com.taxonomy.export.DiagramSelectionConfig;
import com.taxonomy.export.DiagramViewMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenClusterAnalysisFinalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ClusterAnalysisStore store = mock(ClusterAnalysisStore.class);
    private final RequirementArchitectureViewService architecture = mock(RequirementArchitectureViewService.class);
    private final ArchitectureReportMetadataPort metadata = mock(ArchitectureReportMetadataPort.class);
    private final TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
    private final CatalogueSnapshotService snapshots = new CatalogueSnapshotService(nodes,
            new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused"),
            CatalogueRuntimePolicy.fullCatalogue(), mock(CatalogueSourceJournal.class));
    private final FrozenClusterAnalysisFinalizer finalizer = new FrozenClusterAnalysisFinalizer(
            store, snapshots, architecture, metadata, mapper);

    @Test
    void finalizesBoundedFrozenElementsAndMetadataBeforePublishingTerminalResult() {
        var context = prepare();
        finalizer.accept(context);
        verify(store).finalizeResult(eq(context), argThat(result -> {
            var view = result.getArchitectureView();
            assertThat(view.getIncludedElements()).singleElement().satisfies(element -> {
                assertThat(element.getNodeCode()).isEqualTo("IP");
                assertThat(element.getTitle()).isEqualTo("Frozen IP");
                assertThat(element.getHierarchyPath()).isEqualTo("IP");
                assertThat(element.getScoreDetail().rawScore()).isEqualTo(80);
            });
            assertThat(view.getIncludedRelationships()).isEmpty();
            assertThat(view.getNotes()).anyMatch(note -> note.contains("hierarchy-only"));
            assertThat(view.getViewTitle()).isEqualTo("archview.policy.title.trace");
            assertThat(result.getRawScores()).containsExactlyInAnyOrderEntriesOf(Map.of("CP", 55, "IP", 80));
            assertThat(result.getViewContext().basedOnCommit()).isEqualTo("commit");
            return true;
        }));
        assertThat(FrozenCatalogueContext.current()).isNull();
        verifyNoInteractions(nodes, architecture);
    }

    @Test
    void existingRelationEvidenceUsesOnlyTheEvidenceProjectionAndRemainsUnchanged() {
        var context = prepare();
        var state = store.snapshot(context);
        var report = new RelationSearchReport(1, "digest", "frozen-test", List.of(),
                new RelationSearchModel.Result(List.of(), List.of(), List.of(), 0, 0, 1), 0, 2, 1, List.of(), "");
        state.result().setRelationSearchReport(report);
        when(architecture.buildFromEvidence(anyMap(), anyMap(), eq(1), eq(report))).thenAnswer(i -> {
            assertThat(FrozenCatalogueContext.current().source()).isEqualTo(DurableClusterAnalysisExecutionTest.source(context));
            return new RequirementArchitectureView();
        });
        finalizer.accept(context);
        verify(architecture).buildFromEvidence(anyMap(), anyMap(), eq(1), eq(report));
        verify(architecture, never()).build(anyMap(), anyString(), anyInt(), anyList());
        verify(store).finalizeResult(eq(context), argThat(result -> report.equals(result.getRelationSearchReport())
                && result.getArchitectureView() != null));
        verifyNoInteractions(nodes);
    }

    @Test
    void frozenProvisionalHypothesesRemainExplicitCandidatesInTheRestoredArchitecture() {
        var context = prepare();
        var command = store.command(context);
        when(store.command(context)).thenReturn(new AnalyzeRequirementCommand(command.businessText(), true, 2,
                command.provider(), command.username(), command.workspaceContext(), command.provenance(), command.analysisScope()));
        var hypothesis = new RelationHypothesisDto("CP", "stale CP", "IP", "stale IP", "REQUIRES", 0.44,
                "Inferred from effective scores and compatibility matrix");
        store.snapshot(context).result().setProvisionalRelations(List.of(hypothesis));
        finalizer.accept(context);
        verify(store).finalizeResult(eq(context), argThat(result -> {
            // Simulate a durable result round trip; hypotheses remain distinct from verified evidence.
            var restored = mapper.readValue(mapper.writeValueAsString(result), AnalysisResult.class);
            assertThat(restored.getProvisionalRelations()).singleElement().satisfies(value ->
                    assertThat(value.getReasoning()).isEqualTo(hypothesis.getReasoning()));
            assertThat(restored.getRelationSearchReport()).isNull();
            var view = restored.getArchitectureView();
            assertThat(view.getIncludedRelationships()).singleElement().satisfies(relation -> {
                assertThat(relation.getSourceCode()).isEqualTo("CP");
                assertThat(relation.getTargetCode()).isEqualTo("IP");
                assertThat(relation.getRelationType()).isEqualTo("REQUIRES");
                assertThat(relation.getOrigin()).isEqualTo(RelationOrigin.SUGGESTED_CANDIDATE);
                assertThat(relation.getConfidence()).isEqualTo(0.44);
                assertThat(relation.getIncludedBecause()).contains("Provisional", "not verified");
                assertThat(relation.getRequirementEvidence()).isEmpty();
                assertThat(relation.getRelationId()).isNull();
            });
            assertThat(view.getIncludedElements()).extracting(RequirementElementView::getTitle)
                    .containsExactly("Frozen IP", "Frozen CP");
            assertThat(view.getTotalRelationships()).isEqualTo(1);
            assertThat(view.getNotes()).anyMatch(note -> note.contains("Provisional score-only"));
            return true;
        }));
        assertThat(FrozenCatalogueContext.current()).isNull();
        verifyNoInteractions(nodes, architecture);
    }

    @Test
    void deterministicRenderingFailurePublishesExplicitPartialWithoutLosingScores() {
        var context = prepare();
        when(metadata.resolve()).thenThrow(new IllegalStateException("unavailable optional metadata"));
        finalizer.accept(context);
        verify(store).finalizeResult(eq(context), argThat(result ->
                result.getStatus().equals("PARTIAL") && result.getArchitectureView() == null
                        && result.getWarnings().stream().anyMatch(w -> w.startsWith("ARCHITECTURE_VIEW_INCOMPLETE:"))
                        && result.getRawScores().size() == 2));
        assertThat(FrozenCatalogueContext.current()).isNull();
    }

    @Test
    void databaseFailureCanBeRedeliveredAndTerminalDuplicateDoesNoWork() {
        var context = prepare();
        when(store.shard(context, TaxonomyShardRoot.of("CP")))
                .thenThrow(new TransientDataAccessResourceException("temporary database outage"));
        assertThatThrownBy(() -> finalizer.accept(context)).isInstanceOf(TransientDataAccessResourceException.class);
        verify(store, never()).finalizeResult(any(), any());
        when(store.snapshot(context)).thenReturn(DurableClusterAnalysisExecutionTest.snapshot(context, ClusterAnalysisState.COMPLETED));
        clearInvocations(store);
        finalizer.accept(context);
        verify(store).snapshot(context);
        verifyNoMoreInteractions(store);
    }

    private com.taxonomy.analysis.dag.AnalysisOperationContext prepare() {
        var context = DurableClusterAnalysisExecutionTest.context("finalizing");
        var c = DurableClusterAnalysisExecutionTest.command(AnalysisMode.TAXONOMIES_ONLY, "MOCK");
        when(store.command(context)).thenReturn(new AnalyzeRequirementCommand(c.businessText(), true, 1,
                c.provider(), c.username(), c.workspaceContext(), c.provenance(), c.analysisScope()));
        var result = new AnalysisResult(Map.of("CP", 55, "IP", 80), List.of()); result.setStatus("SUCCESS");
        result.setViewContext(new ViewContext("commit", "draft", null, false, false, false));
        when(store.snapshot(context)).thenReturn(new ClusterAnalysisStore.Snapshot(context.operationId(),
                ClusterAnalysisState.FINALIZING, 3, 1, 1, 0, 0, 1, 2, List.of(), result));
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) when(store.shard(context, root)).thenReturn(
                mapper.writeValueAsString(DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code())));
        when(metadata.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        doCallRealMethod().when(metadata).applyTo(any(RequirementArchitectureView.class));
        return context;
    }
}
