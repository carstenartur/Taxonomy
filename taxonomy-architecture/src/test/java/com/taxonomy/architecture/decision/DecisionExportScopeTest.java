package com.taxonomy.architecture.decision;

import com.taxonomy.architecture.decision.DecisionRationaleReport.*;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.architecture.report.DecisionTreeOverview.DecisionTreeRow;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecisionExportScopeTest {
    private final List<TaxonomyNodeDto> tree = List.of(
            node("CP", "Capabilities", 0,
                    node("BP-looking", "Actually a capability", 1),
                    node("CP-zero", "Rejected alternative", 1)),
            node("BP", "Processes", 0, node("CP-looking", "Actually a process", 1)));
    private final WorkspaceContext context = new WorkspaceContext("author", "workspace", "main", "repo");

    @Test
    void completedSelectedAnalysisDoesNotRequireUnselectedRoots() {
        var report = generate(input(Map.of("CP", 80, "BP-looking", 80, "CP-zero", 0))
                .withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY), null), null);
        assertThat(report.status()).isEqualTo(ReportStatus.FINAL);
        assertThat(report.metadata().completenessPercent()).isEqualTo(100.0);
        assertThat(report.scope().reportRoots()).containsExactly("CP");
        assertThat(report.scope().analysisScope().includesRelations()).isFalse();
        assertThat(report.scope().decisionTree().rows()).extracting(DecisionTreeRow::code)
                .containsExactly("CP", "BP-looking", "CP-zero");
    }

    @Test
    void presentationSelectionUsesAncestryAndPreservesSourceFingerprint() {
        var input = input(Map.of("CP", 80, "BP-looking", 80, "CP-zero", 0, "BP", 95, "CP-looking", 95));
        var full = generate(input, null);
        var selected = generate(input, compact("CP"));
        assertThat(selected.leadingLeaves()).extracting(LeafCandidate::code).containsExactly("BP-looking");
        assertThat(selected.leadingLeaves().getFirst().taxonomyRoot()).isEqualTo("CP");
        assertThat(selected.executiveSummary().leadingLeaf().code()).isEqualTo("BP-looking");
        assertThat(selected.chapters()).extracting(DecisionChapter::parentCode).containsExactly("CP");
        assertThat(selected.metadata().analysisSnapshotFingerprintSha256())
                .isEqualTo(full.metadata().analysisSnapshotFingerprintSha256());
        assertThat(selected.metadata().taxonomyDataFingerprintSha256())
                .isEqualTo(full.metadata().taxonomyDataFingerprintSha256());
        assertThat(selected.scope().selectedNodeCodes()).contains("BP-looking").doesNotContain("CP-looking");
        assertThat(selected.scope().analysisScope()).isNull();
    }

    @Test
    void selectingSuccessfulRootDoesNotHideIncompleteSourceAnalysis() {
        var scores = Map.of("CP", 80, "BP-looking", 80, "CP-zero", 0);
        var coverage = AnalysisCoverage.derive(tree, scores, scores, Map.of("BP", "FAILED: unavailable"));
        var report = generate(input(scores).withScope(AnalysisScope.full(), coverage), compact("CP"));
        assertThat(report.status()).isEqualTo(ReportStatus.DRAFT_INCOMPLETE);
        assertThat(report.scope().selectionComplete()).isTrue();
        assertThat(report.scope().analysisComplete()).isFalse();
        assertThat(report.scope().analysisCoverage()).isEqualTo(coverage);
    }

    @Test
    void zeroRootAndMissingAlternativesSurviveWithoutPositiveChapter() {
        var rejected = generate(input(Map.of("CP", 0, "BP", 0)), compact("CP"));
        assertThat(rejected.scope().decisionTree().rows()).extracting(DecisionTreeRow::code).containsExactly("CP");
        assertThat(rejected.scope().decisionTree().rows().getFirst().disposition()).isEqualTo(Disposition.REJECTED);
        assertThat(rejected.scope().recordedReasons()).containsOnlyKeys("CP").containsEntry("CP", "Saved reason for CP");
        assertThat(new tools.jackson.databind.json.JsonMapper().valueToTree(rejected).path("scope").path("recordedReasons").path("CP").asText())
                .isEqualTo("Saved reason for CP");
        var partial = generate(input(Map.of("CP", 80, "BP-looking", 80, "BP", 0)), compact("CP"));
        assertThat(partial.scope().decisionTree().rows()).extracting(DecisionTreeRow::code)
                .containsExactly("CP", "BP-looking", "CP-zero");
        assertThat(partial.scope().decisionTree().rows().getLast().score()).isNull();
        assertThat(partial.scope().selectionComplete()).isFalse();
    }

    @Test
    void rejectsUnknownRootsAndRootsOutsideRecordedAnalysisScope() {
        var input = input(Map.of("CP", 80, "BP-looking", 80, "CP-zero", 0))
                .withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL), null);
        assertThatIllegalArgumentException().isThrownBy(() -> generate(input, compact("unknown")));
        assertThatIllegalArgumentException().isThrownBy(() -> generate(input, compact("BP")));
    }

    @Test
    void explicitEmptySelectionDoesNotWidenToAllRoots() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DecisionReportOptions(
                DecisionReportOptions.Profile.COMPACT, Set.of(), null, null, null));
        assertThat(DecisionReportOptions.full().taxonomyRoots()).isEmpty();
    }

    @Test
    void recordedScopeCannotContradictSuppliedScores() {
        var input = input(Map.of("CP", 0, "BP", 100, "CP-looking", 100))
                .withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL), null);
        assertThatIllegalArgumentException().isThrownBy(() -> generate(input, null));
    }

    @Test
    void sourceFingerprintDistinguishesTaxonomiesOnlyFromFullAnalysis() {
        var input = input(Map.of("CP", 80, "BP-looking", 80, "CP-zero", 0));
        var taxonomyOnly = generate(input.withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY), null), null);
        var full = generate(input.withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL), null), null);
        assertThat(taxonomyOnly.metadata().analysisSnapshotFingerprintSha256())
                .isNotEqualTo(full.metadata().analysisSnapshotFingerprintSha256());
    }

    @Test
    void scoreAdaptationKeepsSelectedRawEvidenceAndRemainsIdempotent() {
        var input = input(Map.of("CP", 80, "BP-looking", 80, "CP-zero", 0, "BP", 95, "CP-looking", 95));
        var details = Map.of("BP-looking", new AnalysisScoreDetail("BP-looking", null, 80, 80, "CP", 80),
                "CP-looking", new AnalysisScoreDetail("CP-looking", null, 95, 95, "BP", 95));
        var adapter = new DecisionRationaleScoreSemanticsAdapter();
        var selected = adapter.adapt(generate(input, compact("CP")), details, Locale.ENGLISH);
        var full = adapter.adapt(generate(input, null), details, Locale.ENGLISH);
        assertThat(selected.scoreDetails()).containsOnlyKeys("BP-looking");
        assertThat(selected.metadata().analysisSnapshotFingerprintSha256())
                .isEqualTo(full.metadata().analysisSnapshotFingerprintSha256());
        assertThat(adapter.adapt(selected, details, Locale.ENGLISH).metadata().analysisSnapshotFingerprintSha256())
                .isEqualTo(selected.metadata().analysisSnapshotFingerprintSha256());
    }

    @Test
    void generatedScoreDescriptionsNeverBecomeRecordedAiReasons() {
        var scores = Map.of("CP", 80, "BP-looking", 64, "CP-zero", 0);
        var base = input(scores);
        var details = Map.of("BP-looking", new AnalysisScoreDetail("BP-looking", AnalysisScoreKind.PRODUCT_SUITABILITY,
                80, 64, "CP", 80));
        var adapter = new DecisionRationaleScoreSemanticsAdapter();
        var enriched = adapter.enrichReasons(Map.of(), details, Locale.ENGLISH);
        var input = new DecisionAnalysisInput(base.businessText(), scores, enriched, "MOCK", "SUCCESS", List.of(),
                List.of(), tree, base.snapshotProvenance(), details)
                .withRecordedReasons(Map.of())
                .withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY), null);
        var report = adapter.adapt(generate(input, compact("CP")), details, Locale.ENGLISH);
        assertThat(report.scope().recordedReasons()).isEmpty();
        assertThat(report.metadata().suppliedReasonCount()).isZero();
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("available for 2 positively scored nodes"));
        assertThat(report.leadingLeaves().getFirst().reasonSource()).isNotEqualTo(ReasonSource.AI_SCORING);
        assertThat(String.join("\n", DecisionReportPresentation.compactReasons(report)))
                .contains("Product suitability: 80%", new DecisionReportLabels("en").deterministicReason())
                .doesNotContain(new DecisionReportLabels("en").aiReason());
    }

    private DecisionAnalysisInput input(Map<String, Integer> scores) {
        var reasons = new LinkedHashMap<String, String>();
        scores.forEach((code, score) -> reasons.put(code, "Saved reason for " + code));
        var provenance = new DecisionRationaleReportService.AnalysisSnapshotProvenance("snapshot", 1L, 2L,
                3L, 1, java.time.Instant.EPOCH, "author", "mock-model", TaxonomyDataFingerprint.sha256(tree), "prompt");
        return new DecisionAnalysisInput("Frozen requirement", scores, reasons, "MOCK", "SUCCESS",
                List.of(), tree, provenance);
    }

    private DecisionRationaleReport generate(DecisionAnalysisInput input, DecisionReportOptions options) {
        var catalogue = mock(TaxonomyCatalogueMetadataService.class);
        var build = mock(DecisionReportBuildMetadataService.class);
        when(catalogue.getMetadata()).thenReturn(new TaxonomyCatalogueMetadataService.CatalogueMetadata(
                "catalogue.xlsx", "v1", "hash", "source", null, null, null, null));
        when(build.current()).thenReturn(new DecisionReportBuildMetadataService.BuildMetadata("1", "commit"));
        return new DecisionRationaleReportService(mock(TaxonomyService.class), catalogue, build, "UTC")
                .generate(input, context, null, Locale.ENGLISH, options);
    }

    private static DecisionReportOptions compact(String root) {
        return new DecisionReportOptions(DecisionReportOptions.Profile.COMPACT, Set.of(root), null, null, null);
    }

    private static TaxonomyNodeDto node(String code, String name, int level, TaxonomyNodeDto... children) {
        var node = new TaxonomyNodeDto();
        node.setCode(code); node.setNameEn(name); node.setLevel(level); node.setChildren(List.of(children));
        return node;
    }
}
