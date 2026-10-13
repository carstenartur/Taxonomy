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
import com.taxonomy.export.DiagramProjectionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialResultRemainsLabelledAfterFinalizationAndRestoreWithoutOpenCoverage(boolean hasCoverage) {
        var context = prepare();
        var original = store.snapshot(context).result();
        original.setStatus("PARTIAL");
        if (hasCoverage) original.setAnalysisCoverage(new AnalysisCoverage(Map.of(), 0, 0, 0));

        finalizer.accept(context);

        verify(store).finalizeResult(eq(context), argThat(result -> {
            var restored = mapper.readValue(mapper.writeValueAsString(result), AnalysisResult.class);
            var diagram = new DiagramProjectionService().projectRaw(restored.getArchitectureView(), "Frozen result");
            assertThat(diagram.title()).contains("PARTIAL").doesNotContain("0 unassessed");
            assertThat(diagram.nodes()).singleElement().satisfies(node -> assertThat(node.id()).isEqualTo("IP-1136"));
            assertThat(restored.getRawScores()).isEqualTo(original.getRawScores());
            return true;
        }));
    }

    @Test
    void finalizesBoundedFrozenElementsAndMetadataBeforePublishingTerminalResult() {
        var context = prepare();
        finalizer.accept(context);
        verify(store).finalizeResult(eq(context), argThat(result -> {
            var view = result.getArchitectureView();
            assertThat(view.getIncludedElements()).singleElement().satisfies(element -> {
                assertThat(element.getNodeCode()).isEqualTo("IP-1136");
                assertThat(element.getTitle()).isEqualTo("Electronic Treatment Records");
                assertThat(element.getHierarchyPath()).isEqualTo("IP-1136");
                assertThat(element.getScoreDetail().rawScore()).isEqualTo(80);
            });
            assertThat(view.getIncludedRelationships()).isEmpty();
            assertThat(view.getNotes()).anyMatch(note -> note.contains("hierarchy-only"));
            assertThat(view.getViewTitle()).isEqualTo("archview.policy.title.trace");
            assertThat(result.getRawScores()).containsExactlyInAnyOrderEntriesOf(Map.of("CP-1023", 55, "IP-1136", 80));
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
        var hypothesis = new RelationHypothesisDto("CP-1023", "Communication and Information System Capabilities",
                "IP-1136", "Electronic Treatment Records", "REQUIRES", 0.44,
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
                assertThat(relation.getSourceCode()).isEqualTo("CP-1023");
                assertThat(relation.getTargetCode()).isEqualTo("IP-1136");
                assertThat(relation.getRelationType()).isEqualTo("REQUIRES");
                assertThat(relation.getOrigin()).isEqualTo(RelationOrigin.SUGGESTED_CANDIDATE);
                assertThat(relation.getConfidence()).isEqualTo(0.44);
                assertThat(relation.getIncludedBecause()).contains("Provisional", "not verified");
                assertThat(relation.getRequirementEvidence()).isEmpty();
                assertThat(relation.getRelationId()).isNull();
            });
            assertThat(view.getIncludedElements()).extracting(RequirementElementView::getTitle)
                    .containsExactly("Electronic Treatment Records", "Communication and Information System Capabilities");
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
                result.getStatus().equals("PARTIAL") && result.getArchitectureView() != null
                        && "PARTIAL".equals(result.getArchitectureView().getAnalysisStatus())
                        && result.getArchitectureView().getIncludedElements().stream()
                                .map(RequirementElementView::getNodeCode).toList().equals(List.of("IP-1136"))
                        && result.getArchitectureView().getIncludedRelationships().isEmpty()
                        && result.getWarnings().stream().anyMatch(w -> w.startsWith("ARCHITECTURE_VIEW_INCOMPLETE:"))
                        && result.getRawScores().size() == 2));
        assertThat(FrozenCatalogueContext.current()).isNull();
    }

    @Test
    void scoredNodesWithoutEmbeddedTreeUseTheBoundFrozenSnapshot() {
        var context = prepare();
        var result = store.snapshot(context).result();
        result.setTree(null);
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) {
            var frozenRoot = root.code().equals("CP") || root.code().equals("IP")
                    ? retainedSnapshot(context, root.code())
                    : DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code());
            when(store.shard(context, root)).thenReturn(mapper.writeValueAsString(frozenRoot));
        }

        finalizer.accept(context);

        verify(store).finalizeResult(eq(context), argThat(finalized -> {
            var view = finalized.getArchitectureView();
            assertThat(finalized.getRawScores()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("CP-1023", 55, "IP-1136", 80));
            assertThat(view.getIncludedElements()).extracting(RequirementElementView::getNodeCode)
                    .containsExactlyInAnyOrder("CP-1023", "IP-1136");
            assertThat(view.getIncludedElements()).extracting(RequirementElementView::getTitle)
                    .containsExactlyInAnyOrder("Communication and Information System Capabilities",
                            "Electronic Treatment Records");
            assertThat(view.getIncludedRelationships()).isEmpty();
            return true;
        }));
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
        // Like durable root results, the merged result retains its own catalogue tree.
        var result = new AnalysisResult(Map.of("CP-1023", 55, "IP-1136", 80), List.of(retained("CP"), retained("IP")));
        result.setStatus("SUCCESS");
        result.setViewContext(new ViewContext("commit", "draft", null, false, false, false));
        when(store.snapshot(context)).thenReturn(new ClusterAnalysisStore.Snapshot(context.operationId(),
                ClusterAnalysisState.FINALIZING, 3, 1, 1, 0, 0, 1, 2, List.of(), result));
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) when(store.shard(context, root)).thenReturn(
                mapper.writeValueAsString(DurableClusterAnalysisExecutionTest.root(DurableClusterAnalysisExecutionTest.source(context), root.code())));
        when(metadata.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        doCallRealMethod().when(metadata).applyTo(any(RequirementArchitectureView.class));
        return context;
    }

    private static TaxonomyNodeDto retained(String root) {
        if ("CP".equals(root)) {
            var parent = new TaxonomyNodeDto(); parent.setCode("CP-1000"); parent.setTaxonomyRoot(root);
            parent.setNameEn("Capabilities"); parent.setLevel(1);
            var child = new TaxonomyNodeDto(); child.setCode("CP-1023"); child.setTaxonomyRoot(root);
            child.setNameEn("Communication and Information System Capabilities");
            child.setParentCode("CP-1000"); child.setLevel(2);
            parent.getChildren().add(child);
            return parent;
        } else if ("IP".equals(root)) {
            var child = new TaxonomyNodeDto(); child.setCode("IP-1136"); child.setTaxonomyRoot(root);
            child.setNameEn("Electronic Treatment Records"); child.setLevel(1);
            return child;
        } else {
            throw new IllegalArgumentException("Unsupported taxonomy fixture root: " + root);
        }
    }

    private static RootCatalogueSnapshot retainedSnapshot(
            com.taxonomy.analysis.dag.AnalysisOperationContext context, String root) {
        var source = DurableClusterAnalysisExecutionTest.source(context);
        var nodes = new java.util.ArrayList<RootCatalogueSnapshot.Node>();
        nodes.add(snapshotNode(root, root, null, "Frozen " + root, 0));
        if ("CP".equals(root)) {
            nodes.add(snapshotNode("CP-1000", root, root, "Capabilities", 1));
            nodes.add(snapshotNode("CP-1023", root, "CP-1000",
                    "Communication and Information System Capabilities", 2));
        } else if ("IP".equals(root)) {
            nodes.add(snapshotNode("IP-1136", root, root, "Electronic Treatment Records", 1));
        }
        var overlay = new CatalogueOverlayService.OverlayMetadata(false, "fixture", null, null, null, 0);
        return new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, source, root, nodes,
                overlay, DurableClusterAnalysisExecutionTest.provenance());
    }

    private static RootCatalogueSnapshot.Node snapshotNode(
            String code, String root, String parent, String name, int level) {
        var node = new com.taxonomy.catalog.model.TaxonomyNode();
        node.setCode(code); node.setTaxonomyRoot(root); node.setParentCode(parent);
        node.setNameEn(name); node.setLevel(level);
        return RootCatalogueSnapshot.Node.capture(node,
                new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false);
    }
}
