package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.snapshot.CatalogueRuntimePolicy;
import com.taxonomy.catalog.snapshot.CatalogueSnapshotService;
import com.taxonomy.catalog.snapshot.RootCatalogueSnapshot;
import com.taxonomy.catalog.service.CatalogueOverlayService.NodeMetadata;
import com.taxonomy.catalog.service.CatalogueOverlayService.OverlayMetadata;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScoreSemantics;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.export.DiagramSelectionConfig;
import com.taxonomy.export.DiagramViewMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FrozenClusterAnalysisSnapshotProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ClusterAnalysisStore store = mock(ClusterAnalysisStore.class);
    private final RequirementArchitectureViewService architecture = mock(RequirementArchitectureViewService.class);
    private final ArchitectureReportMetadataPort metadata = mock(ArchitectureReportMetadataPort.class);
    private final TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
    private final CatalogueSnapshotService snapshots = new CatalogueSnapshotService(repository,
            new CatalogueOverlayService(mapper, new DefaultResourceLoader(), false, "unused"),
            CatalogueRuntimePolicy.fullCatalogue(), mock(CatalogueSourceJournal.class));
    private final FrozenClusterAnalysisFinalizer finalizer = new FrozenClusterAnalysisFinalizer(
            store, snapshots, architecture, metadata, mapper);

    @Test
    void emptyResultTreeProjectsAcceptedCodesFromRecordedFrozenSnapshot() {
        var context = prepare(false);

        finalizer.accept(context);

        verify(store).finalizeResult(eq(context), argThat(result -> {
            var view = result.getArchitectureView();
            assertThat(view.getIncludedElements()).extracting(element -> element.getNodeCode())
                    .containsExactly("IP-1018");
            assertThat(view.getIncludedElements().getFirst().getTitle()).isEqualTo("Asset State Reports");
            assertThat(view.getIncludedElements().getFirst().getHierarchyPath()).isEqualTo("IP > IP-2053 > IP-1018");
            assertThat(result.getRawScores()).containsEntry("IP-1018", 80.0);
            assertThat(result.getScores()).containsEntry("IP-1018", 80.0);
            assertThat(result.getScoreDetails()).containsEntry("IP-1018",
                    new AnalysisScoreDetail("IP-1018", AnalysisScoreKind.HIERARCHICAL_RELEVANCE, 80, 80, "IP-2053", null));
            assertThat(result.getViewContext().basedOnCommit()).isEqualTo("commit");
            return true;
        }));
        assertThat(com.taxonomy.catalog.snapshot.FrozenCatalogueContext.current()).isNull();
        verifyNoInteractions(repository, architecture);
    }

    @Test
    void metadataFailureAfterReconstructionKeepsSnapshotProjectionOutsideBinding() {
        var context = prepare(false);
        when(metadata.resolve()).thenThrow(new IllegalStateException("metadata unavailable"));

        finalizer.accept(context);

        verify(store).finalizeResult(eq(context), argThat(result -> {
            assertThat(com.taxonomy.catalog.snapshot.FrozenCatalogueContext.current()).isNull();
            assertThat(result.getStatus()).isEqualTo("PARTIAL");
            assertThat(result.getArchitectureView().getIncludedElements())
                    .extracting(element -> element.getNodeCode()).containsExactly("IP-1018");
            assertThat(result.getArchitectureView().getIncludedElements().getFirst().getTitle())
                    .isEqualTo("Asset State Reports");
            return true;
        }));
        verifyNoInteractions(repository, architecture);
    }

    @Test
    void nonemptyResultTreeRemainsAuthoritativeOverSnapshotFallback() {
        var context = prepare(true);

        finalizer.accept(context);

        verify(store).finalizeResult(eq(context), argThat(result -> {
            assertThat(result.getArchitectureView().getIncludedElements()).singleElement().satisfies(element -> {
                assertThat(element.getNodeCode()).isEqualTo("IP-1018");
                assertThat(element.getTitle()).isEqualTo("Retained result tree");
            });
            return true;
        }));
        verifyNoInteractions(repository, architecture);
    }

    private AnalysisOperationContext prepare(boolean nonemptyTree) {
        var context = DurableClusterAnalysisExecutionTest.context("snapshot-projection");
        var request = DurableClusterAnalysisExecutionTest.command(AnalysisMode.TAXONOMIES_ONLY, "MOCK");
        when(store.command(context)).thenReturn(new AnalyzeRequirementCommand(request.businessText(), true, 5,
                request.provider(), request.username(), request.workspaceContext(), request.provenance(),
                request.analysisScope()));
        var resultTree = nonemptyTree ? List.of(dto("IP", null, "IP", 0, "IP"),
                dto("IP-1018", "IP", "IP", 1, "Retained result tree")) : List.<TaxonomyNodeDto>of();
        if (nonemptyTree) {
            resultTree.getFirst().getChildren().add(resultTree.get(1));
            resultTree = List.of(resultTree.getFirst());
        }
        var result = new AnalysisResult(Map.of("IP-1018", 80), resultTree);
        result.setScoreSemanticsContext(Map.of("IP-1018",
                new AnalysisScoreSemantics.NodeContext("IP-2053", "PRODUCT_FAMILY")));
        result.setStatus("SUCCESS");
        result.setViewContext(new ViewContext("commit", "draft", null, false, false, false));
        when(store.snapshot(context)).thenReturn(new ClusterAnalysisStore.Snapshot(context.operationId(),
                ClusterAnalysisState.FINALIZING, 3, 1, 1, 0, 0, 1, 2, List.of(), result));
        for (var root : TaxonomyShardRoot.DEFAULT_ROOTS) {
            when(store.shard(context, root)).thenReturn(mapper.writeValueAsString(snapshot(context, root.code())));
        }
        when(metadata.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        doCallRealMethod().when(metadata).applyTo(any(RequirementArchitectureView.class));
        return context;
    }

    private static RootCatalogueSnapshot snapshot(AnalysisOperationContext context, String root) {
        var nodes = new ArrayList<RootCatalogueSnapshot.Node>();
        nodes.add(snapshotNode(root, null, root, 0, "Frozen " + root, "CATEGORY"));
        if (root.equals("IP")) {
            nodes.add(snapshotNode("IP-2053", "IP", root, 1, "Resource Management Information Products", "PRODUCT_FAMILY"));
            nodes.add(snapshotNode("IP-1018", "IP-2053", root, 2, "Asset State Reports", "PRODUCT_FAMILY"));
        }
        return new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION,
                DurableClusterAnalysisExecutionTest.source(context), root, nodes,
                new OverlayMetadata(false, "fixture", null, null, null, 0),
                DurableClusterAnalysisExecutionTest.provenance());
    }

    private static RootCatalogueSnapshot.Node snapshotNode(
            String code, String parent, String root, int level, String name, String role) {
        return new RootCatalogueSnapshot.Node(null, code, null, name, name, null, null, parent, root, level,
                null, null, null, null, null, "active", new NodeMetadata(role, List.of(), 1, false, null), false);
    }

    private static TaxonomyNodeDto dto(String code, String parent, String root, int level, String name) {
        var node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parent);
        node.setTaxonomyRoot(root);
        node.setLevel(level);
        node.setNameEn(name);
        return node;
    }
}
