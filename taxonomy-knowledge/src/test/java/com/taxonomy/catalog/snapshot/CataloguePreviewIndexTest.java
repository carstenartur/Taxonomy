package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.CatalogueOverlayService.NodeMetadata;
import com.taxonomy.catalog.service.CatalogueOverlayService.OverlayMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CataloguePreviewIndexTest {
    private static final CatalogueSourceIdentity SOURCE = new CatalogueSourceIdentity("repo", "desk", "main", "v1");
    private static final NodeMetadata META = new NodeMetadata("CATEGORY", List.of(), 1, false, null);

    @Test
    void independentlyRanksMatchingNodesAndUsesPathOnlyNodesForNavigation() {
        var result = index().query("permit", Set.of());
        assertThat(result.matches()).extracting(CataloguePreviewIndex.Match::code)
                .containsExactly("BP.review", "IP.form");
        assertThat(result.matches()).extracting(CataloguePreviewIndex.Match::score).containsOnly(100.0);
        assertThat(result.ancestors()).extracting(CataloguePreviewIndex.Match::code)
                .containsExactly("BP", "IP", "BP.office");
        assertThat(result.ancestors()).extracting(CataloguePreviewIndex.Match::score).containsOnly(0.0);
        assertThat(result.associations()).containsExactly(
                new CataloguePreviewIndex.Association("BP.review", "IP.form", List.of("permit")));
    }

    @Test
    void doesNotPropagateScoresToRelatedNodesOnChangedTextOrAcrossRootFilters() {
        var index = index();
        var english = index.query("transparency", Set.of());
        assertThat(english.matches()).extracting(CataloguePreviewIndex.Match::code).containsExactly("IP.form");
        assertThat(english.matches().getFirst().score()).isEqualTo(65.0);
        assertThat(english.associations()).isEmpty();
        // The two independently scored titles share "permit", but this wording refers to other topics.
        var unrelated = index.query("review forms", Set.of());
        assertThat(unrelated.matches()).extracting(CataloguePreviewIndex.Match::code)
                .containsExactlyInAnyOrder("BP.review", "IP.form");
        assertThat(unrelated.associations()).isEmpty();
        var german = index.query("Anträge", Set.of());
        assertThat(german.associations()).containsExactly(
                new CataloguePreviewIndex.Association("BP.review", "IP.form", List.of("antrag")));
        var scoped = index.query("Anträge", Set.of("BP"));
        assertThat(scoped.matches()).extracting(CataloguePreviewIndex.Match::code).containsExactly("BP.review");
        assertThat(scoped.ancestors()).extracting(CataloguePreviewIndex.Match::root).containsOnly("BP");
        assertThat(scoped.associations()).isEmpty();
        assertThat(index.query("quuxxyz", Set.of()).matches()).isEmpty();
        assertThat(index.query("  ", Set.of())).isEqualTo(new CataloguePreviewIndex.Result(List.of(), List.of(), List.of()));
        assertThatThrownBy(() -> index.query("permit", Set.of("UNKNOWN"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundsTiesPerRootAndDoesNotPrecomputeGenericHeadingLinks() {
        var bp = new ArrayList<RootCatalogueSnapshot.Node>();
        bp.add(node("BP", null, "BP", 0, "Tasks", null, null));
        for (int i = 0; i < 17; i++) bp.add(node("BP." + i, "BP", "BP", 1, "Permit", null, null));
        var ip = root("IP", List.of(node("IP", null, "IP", 0, "Permits", null, null)));
        var result = new CataloguePreviewIndex(List.of(root("BP", bp), ip)).query("permit", Set.of());
        assertThat(result.matches()).hasSize(13);
        assertThat(result.matches()).extracting(CataloguePreviewIndex.Match::code).isSorted();
        assertThat(result.associations()).isEmpty();
    }

    @Test
    void doesNotConflateEmergencyWithEmergentOrUseSurfaceTermsFromAnotherEndpoint() {
        var bp = root("BP", List.of(node("BP", null, "BP", 0, "Emergency planning", null, null)));
        var ip = root("IP", List.of(node("IP", null, "IP", 0, "Emergent plans", null, null)));
        var ci = root("CI", List.of(node("CI", null, "CI", 0, "Coordinate emergencies", null, null)));
        var result = new CataloguePreviewIndex(List.of(bp, ip, ci)).query("emergency", Set.of());
        assertThat(result.matches()).extracting(CataloguePreviewIndex.Match::code).containsExactly("BP", "CI");
        assertThat(result.associations()).containsExactly(new CataloguePreviewIndex.Association("BP", "CI", List.of("emergency")));
    }

    @Test
    void rejectsDuplicateRootsAndMixedPublicationIdentities() {
        var bp = root("BP", List.of(node("BP", null, "BP", 0, "Tasks", null, null)));
        assertThatThrownBy(() -> new CataloguePreviewIndex(List.of(bp, bp))).isInstanceOf(IllegalArgumentException.class);
        var foreign = new RootCatalogueSnapshot(2, new CatalogueSourceIdentity("other", "desk", "main", "v1"),
                "IP", List.of(node("IP", null, "IP", 0, "Forms", null, null)), bp.overlayMetadata(), bp.catalogueProvenance());
        assertThatThrownBy(() -> new CataloguePreviewIndex(List.of(bp, foreign))).isInstanceOf(IllegalArgumentException.class);
    }

    private static CataloguePreviewIndex index() {
        return new CataloguePreviewIndex(List.of(
                root("BP", List.of(node("BP", null, "BP", 0, "Tasks", "Aufgaben", null),
                        node("BP.office", "BP", "BP", 1, "Front desk", "Büro", null),
                        node("BP.review", "BP.office", "BP", 2, "Review permit", "Antrag prüfen", null))),
                root("IP", List.of(node("IP", null, "IP", 0, "Documents", "Dokumente", null),
                        node("IP.form", "IP", "IP", 1, "Permit forms", "Antrag erfassen", "Public transparency")))));
    }

    private static RootCatalogueSnapshot root(String code, List<RootCatalogueSnapshot.Node> nodes) {
        return new RootCatalogueSnapshot(RootCatalogueSnapshot.SCHEMA_VERSION, SOURCE, code, nodes,
                new OverlayMetadata(false, "preview", "test", "v1", null, 0), CatalogueSnapshotServiceTest.provenance(null));
    }

    private static RootCatalogueSnapshot.Node node(String code, String parent, String root, int level, String en,
                                                  String de, String description) {
        var node = new TaxonomyNode();
        node.setCode(code); node.setTaxonomyRoot(root); node.setLevel(level); node.setParentCode(parent);
        node.setNameEn(en); node.setNameDe(de); node.setDescriptionEn(description);
        return RootCatalogueSnapshot.Node.capture(node, META, false);
    }
}
