package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.provenance.CatalogueSourceJournal;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.AppInitializationStateService;
import com.taxonomy.catalog.service.CatalogueOverlayService;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.catalog.service.SearchService;
import com.taxonomy.catalog.service.TaxonomyService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CatalogueSnapshotServiceTest {
    private static final String OVERLAY_DIGEST = "a".repeat(64);
    private static final CatalogueSourceIdentity CP_SOURCE =
            new CatalogueSourceIdentity("repo-a", "workspace-a", "branch-a", "commit-a");
    private static final CatalogueSourceIdentity IP_SOURCE =
            new CatalogueSourceIdentity("repo-b", "workspace-b", "branch-b", "commit-b");
    private final TaxonomyNodeRepository repository = mock(TaxonomyNodeRepository.class);
    private final CatalogueOverlayService overlay = new CatalogueOverlayService(
            new ObjectMapper(), new DefaultResourceLoader(), false, "unused");
    private final CatalogueRuntimePolicy policy = new CatalogueRuntimePolicy("all", "CP,IP");
    private final CatalogueSnapshotService snapshots = new CatalogueSnapshotService(repository, overlay, policy, sources(null));
    private final TaxonomyService taxonomy = taxonomy();

    @Test
    void capturesOnlyRequestedRootAndJsonRoundTripPreservesImmutableScalars() throws Exception {
        TaxonomyNode child = node("shared-node", "CP", "CP", "Frozen title");
        child.setSource("official fixture provenance");
        child.setSortOrder(17);
        when(repository.findByTaxonomyRootOrderByLevelAscNameEnAsc("CP"))
                .thenReturn(List.of(node("CP", "CP", null, "Capabilities"), child));

        RootCatalogueSnapshot captured = snapshots.captureRoot(CP_SOURCE, "CP");
        child.setNameEn("Changed after admission");
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsString(captured), RootCatalogueSnapshot.class);
        assertThat(restored).isEqualTo(captured);
        assertThatThrownBy(() -> restored.nodes().clear()).isInstanceOf(UnsupportedOperationException.class);
        try (var ignored = snapshots.bind(CP_SOURCE, Set.of("CP"), List.of(restored))) {
            assertThat(taxonomy.getRootCodes()).containsExactly("CP");
            var frozen = taxonomy.getNodeByCode("shared-node");
            assertThat(frozen.getNameEn()).isEqualTo("Frozen title");
            assertThat(frozen.getSource()).isEqualTo("official fixture provenance");
            assertThat(frozen.getSortOrder()).isEqualTo(17);
            frozen.setNameEn("Accidental caller mutation");
            assertThat(taxonomy.getNodeByCode("shared-node").getNameEn()).isEqualTo("Frozen title");
            assertThat(taxonomy.getFullTree().getFirst().getChildren().getFirst().getNameEn())
                    .isEqualTo("Frozen title");
        }
        verify(repository).findByTaxonomyRootOrderByLevelAscNameEnAsc("CP");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void concurrentSourcesWithOverlappingIdsKeepTheirOwnHierarchyAndOverlay() throws Exception {
        RootCatalogueSnapshot cp = snapshot(CP_SOURCE, "CP", "Capability context", false);
        RootCatalogueSnapshot ip = snapshot(IP_SOURCE, "IP", "Product context", true);
        CyclicBarrier together = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var cpWork = executor.submit(() -> readBound(cp, together));
            var ipWork = executor.submit(() -> readBound(ip, together));
            assertThat(cpWork.get(10, TimeUnit.SECONDS)).contains("capability context", "CATEGORY", "CP");
            assertThat(ipWork.get(10, TimeUnit.SECONDS)).contains("classification", "PRODUCT", "IP");
        }
        assertThat(FrozenCatalogueContext.current()).isNull();
        verifyNoInteractions(repository);
    }

    private String readBound(RootCatalogueSnapshot snapshot, CyclicBarrier together) throws Exception {
        try (var ignored = snapshots.bind(snapshot.source(), Set.of(snapshot.rootCode()), List.of(snapshot))) {
            together.await(5, TimeUnit.SECONDS);
            var child = taxonomy.getChildrenOf(snapshot.rootCode()).getFirst();
            assertThat(child.getCode()).isEqualTo("shared-node");
            // Caller-supplied mutable data must never override frozen assessment evidence.
            child.setDescriptionEn("FOREIGN DESCRIPTION");
            String description = taxonomy.getAssessmentDescriptions(List.of(child)).get("shared-node");
            assertThat(description).doesNotContain("FOREIGN DESCRIPTION");
            return description.toLowerCase(java.util.Locale.ROOT) + " "
                    + taxonomy.getRootCodes() + " " + overlay.analysisRole("shared-node");
        }
    }

    @Test
    void rejectsForeignMissingAndUnassignedShardsWithoutFallingBack() {
        RootCatalogueSnapshot cp = snapshot(CP_SOURCE, "CP", "Capability context", false);
        for (var foreign : List.of(
                new CatalogueSourceIdentity("other-repo", "workspace-a", "branch-a", "commit-a"),
                new CatalogueSourceIdentity("repo-a", "other-workspace", "branch-a", "commit-a"),
                new CatalogueSourceIdentity("repo-a", null, "branch-a", "commit-a"),
                new CatalogueSourceIdentity("repo-a", "workspace-a", "other-branch", "commit-a"),
                new CatalogueSourceIdentity("repo-a", "workspace-a", "branch-a", "other-commit"))) {
            assertThatThrownBy(() -> snapshots.bind(foreign, Set.of("CP"), List.of(cp)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
        }
        assertThatThrownBy(() -> snapshots.bind(CP_SOURCE, Set.of("CP", "IP"), List.of(cp)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("roots");
        var cpWorker = new CatalogueSnapshotService(repository, overlay,
                new CatalogueRuntimePolicy("worker", "CP"), sources(null));
        assertThatThrownBy(() -> cpWorker.bind(IP_SOURCE, Set.of("IP"),
                List.of(snapshot(IP_SOURCE, "IP", "Product context", true))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("configured");
        try (var ignored = snapshots.bind(CP_SOURCE, Set.of("CP"), List.of(cp))) {
            assertThatThrownBy(() -> taxonomy.getNodeByCode("IP"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("snapshot");
            assertThatThrownBy(() -> taxonomy.getChildrenOf("IP"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> overlay.isProduct("IP"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new SearchService().search("title", 10))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("source");
            assertThatThrownBy(() -> new LocalEmbeddingService().scoreNodes("requirement", List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("source");
        }
        verifyNoInteractions(repository);
    }

    @Test
    void closingAfterExceptionRestoresLocalCataloguePath() {
        var local = node("IP", "IP", null, "Current catalogue");
        when(repository.findByParentIsNullOrderByCodeAsc()).thenReturn(List.of(local));
        assertThatThrownBy(() -> {
            try (var ignored = snapshots.bind(CP_SOURCE, Set.of("CP"),
                    List.of(snapshot(CP_SOURCE, "CP", "Capability context", false)))) {
                assertThat(taxonomy.getRootCodes()).containsExactly("CP");
                throw new IllegalStateException("provider failure");
            }
        }).hasMessage("provider failure");
        assertThat(taxonomy.getRootNodes()).containsExactly(local);
        verify(repository).findByParentIsNullOrderByCodeAsc();
    }

    @Test
    void rejectsUnrecognizedFrozenAnalysisRole() {
        assertThatThrownBy(() -> RootCatalogueSnapshot.Node.capture(
                node("shared-node", "CP", "CP", "Child"),
                new CatalogueOverlayService.NodeMetadata("UNRECOGNIZED", List.of(), 1, false, null), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("analysis role");
    }

    @Test
    void rejectsRootsFromDifferentCatalogueGenerationsEvenWithTheSameOperationAndOverlay() {
        var cp = snapshot(CP_SOURCE, "CP", "Capabilities", false);
        var ip = snapshot(CP_SOURCE, "IP", "Products", false);
        var old = cp.catalogueProvenance();
        var newer = new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000002",
                old.createdAt(), old.workbook(), old.overlay(), old.relations());
        var roots = List.of(new RootCatalogueSnapshot(2, CP_SOURCE, "CP", List.of(cp.nodes().getFirst()),
                        cp.overlayMetadata(), old),
                new RootCatalogueSnapshot(2, CP_SOURCE, "IP", List.of(ip.nodes().getFirst()), ip.overlayMetadata(), newer));
        assertThatThrownBy(() -> snapshots.bind(CP_SOURCE, Set.of("CP", "IP"), roots))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("generation");
        assertThatThrownBy(() -> new RootCatalogueSnapshot(2, CP_SOURCE, "CP", cp.nodes(), cp.overlayMetadata(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("catalogueProvenance");
    }

    @Test
    void rejectsIncompleteAndCyclicSnapshotHierarchies() {
        var valid = snapshot(CP_SOURCE, "CP", "Capability context", false);
        assertThatThrownBy(() -> new RootCatalogueSnapshot(1, CP_SOURCE, "CP", valid.nodes(), valid.overlayMetadata(), valid.catalogueProvenance()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema");
        assertThatThrownBy(() -> new RootCatalogueSnapshot(2, CP_SOURCE, "CP", List.of(valid.nodes().getLast()),
                valid.overlayMetadata(), valid.catalogueProvenance())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("root");
        var cycle = RootCatalogueSnapshot.Node.capture(node("shared-node", "CP", "shared-node", "Child"),
                valid.nodes().getLast().metadata(), false);
        assertThatThrownBy(() -> new RootCatalogueSnapshot(2, CP_SOURCE, "CP", List.of(valid.nodes().getFirst(), cycle),
                valid.overlayMetadata(), valid.catalogueProvenance())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Cycle");
    }

    static RootCatalogueSnapshot snapshot(CatalogueSourceIdentity source, String root, String title,
                                          boolean product) {
        var metadata = new CatalogueOverlayService.NodeMetadata(product ? "PRODUCT" : "CATEGORY",
                List.of(), 1, false, null);
        return new RootCatalogueSnapshot(2, source, root,
                List.of(RootCatalogueSnapshot.Node.capture(node(root, root, null, title),
                                new CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false),
                        RootCatalogueSnapshot.Node.capture(node("shared-node", root, root, "Child"),
                                metadata, product)),
                new CatalogueOverlayService.OverlayMetadata(true, "frozen", "fixture", "v1", OVERLAY_DIGEST, 1), provenance(OVERLAY_DIGEST));
    }

    static CatalogueSourceJournal sources(String overlayDigest) {
        var journal = mock(CatalogueSourceJournal.class);
        when(journal.lockForCapture()).thenReturn(provenance(overlayDigest));
        return journal;
    }

    static CatalogueSourceJournal.Snapshot provenance(String overlayDigest) {
        var unknown = new CatalogueSourceJournal.InputReference(CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
        return new CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                unknown, new CatalogueSourceJournal.InputReference(overlayDigest == null ? CatalogueSourceJournal.Use.NOT_USED
                : CatalogueSourceJournal.Use.APPLIED, overlayDigest, 0), unknown);
    }

    static TaxonomyNode node(String code, String root, String parent, String title) {
        var node = new TaxonomyNode();
        node.setCode(code);
        node.setTaxonomyRoot(root);
        node.setParentCode(parent);
        node.setNameEn(title);
        node.setDescriptionEn(title + " description");
        node.setLevel(parent == null ? 0 : 1);
        return node;
    }

    private TaxonomyService taxonomy() {
        var service = new TaxonomyService(repository, mock(TaxonomyRelationRepository.class),
                new AppInitializationStateService());
        ReflectionTestUtils.setField(service, "catalogueOverlayService", overlay);
        return service;
    }
}
