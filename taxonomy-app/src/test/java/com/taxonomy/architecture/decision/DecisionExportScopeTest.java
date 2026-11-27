package com.taxonomy.architecture.decision;

import com.taxonomy.reporting.render.decision.DecisionReportLabels;
import com.taxonomy.reporting.render.decision.DecisionReportPresentation;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport;
import com.taxonomy.reporting.api.decision.DecisionReportOptions;
import com.taxonomy.reporting.api.document.DecisionTreeOverview;

import com.taxonomy.reporting.api.decision.DecisionRationaleReport.*;
import com.taxonomy.architecture.decision.DecisionRationaleReportService.DecisionAnalysisInput;
import com.taxonomy.reporting.api.document.DecisionTreeOverview.DecisionTreeRow;
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
    void liveDecisionHttpAcceptsScorelessClaimsAndRejectsEmptyEvidence() throws Exception {
        var taxonomy = mock(TaxonomyService.class);
        var process = new com.taxonomy.catalog.model.TaxonomyNode();
        process.setCode("BP");
        process.setTaxonomyRoot("BP");
        process.setNameEn("Processes");
        when(taxonomy.getRootNodes()).thenReturn(List.of(process));
        when(taxonomy.getChildrenMap()).thenReturn(Map.of());
        when(taxonomy.getFingerprintTree()).thenReturn(tree);
        when(taxonomy.toFingerprintTree(any())).thenReturn(tree);
        var catalogue = mock(TaxonomyCatalogueMetadataService.class);
        when(catalogue.getMetadata()).thenReturn(new TaxonomyCatalogueMetadataService.CatalogueMetadata(
                "catalogue.xlsx", "v1", "hash", "source", null, null, null, null));
        var build = mock(DecisionReportBuildMetadataService.class);
        when(build.current()).thenReturn(new DecisionReportBuildMetadataService.BuildMetadata("1", "commit"));
        var reports = new DecisionRationaleReportService(taxonomy, catalogue, build, "UTC");
        var repositories = mock(com.taxonomy.versioning.service.RepositoryStateService.class);
        when(repositories.readSelectedViewContext(context)).thenReturn(Optional.of(
                new ViewContext("commit", "main", null, false, false, false)));
        var workspaces = mock(com.taxonomy.workspace.service.WorkspaceResolver.class);
        when(workspaces.resolveUnambiguousContextForRead()).thenReturn(context);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var renderers = new com.taxonomy.reporting.render.document.ReportRendererRegistry(List.of(
                new com.taxonomy.reporting.render.decision.DecisionRationaleJsonRenderer(mapper)));
        var controller = new com.taxonomy.composition.report.DecisionRationaleReportController(reports, renderers,
                repositories, workspaces, new DecisionRationaleScoreSemanticsAdapter(), taxonomy);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        var base = Map.<String, Object>of("businessText", "Frozen requirement", "scores", Map.of());
        var payload = new LinkedHashMap<>(base);
        payload.put("nodeAssessments", Map.of("BP", List.of(unresolved("Which retention interval?"))));
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/decision-report/json").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(payload)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse();
        var json = mapper.readTree(response.getContentAsByteArray());
        assertThat(json.path("nodeAssessments").path("BP").get(0).path("question").asString())
                .isEqualTo("Which retention interval?");
        assertThat(json.path("metadata").path("evaluatedNodeCount").asInt()).isZero();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/decision-report/json").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(base)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }

    @Test
    void selectedExportsKeepOnlyInScopeScorelessClaimsAndTheWholeSourceDigest() throws Exception {
        var selectedClaim = unresolved("Selected question?");
        var foreignClaim = unresolved("Other root question?");
        var input = input(Map.of()).withNodeAssessments(Map.of(
                "BP-looking", List.of(selectedClaim), "CP-looking", List.of(foreignClaim)));
        var full = generate(input, null);
        var selected = generate(input, compact("CP"));
        assertThat(selected.nodeAssessments()).containsOnlyKeys("BP-looking");
        assertThat(full.nodeAssessments()).containsOnlyKeys("BP-looking", "CP-looking");
        assertThat(selected.metadata().analysisSnapshotFingerprintSha256())
                .isEqualTo(full.metadata().analysisSnapshotFingerprintSha256());
        assertThat(selected.scope().selectedNodeCodes()).contains("BP-looking", "CP", "CP-zero");

        var diagrams = new com.taxonomy.reporting.render.decision.DecisionChapterDiagramRenderer();
        var json = new tools.jackson.databind.ObjectMapper();
        var bytes = new com.taxonomy.reporting.render.decision.DecisionRationaleJsonRenderer(json)
                .render(com.taxonomy.extension.api.report.ReportRenderContext.ofPayload(selected)).bytes();
        var claims = json.readTree(bytes).path("nodeAssessments");
        assertThat(claims.path("BP-looking").get(0).path("question").asString()).isEqualTo("Selected question?");
        assertThat(claims.has("CP-looking")).isFalse();
        var html = new com.taxonomy.reporting.render.decision.DecisionRationaleHtmlRenderer(diagrams).render(selected);
        assertThat(html).contains("Selected question?", "Nodes with recorded evidence: 1", "Not assessed: 2")
                .doesNotContain("Other root question?");
        var word = new com.taxonomy.reporting.render.decision.DecisionRationaleDocxRenderer(diagrams).render(selected);
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(word));
                var extractor = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(document)) {
            assertThat(extractor.getText()).contains("Selected question?", "Nodes with recorded evidence: 1", "Not assessed: 2")
                    .doesNotContain("Other root question?");
        }
    }

    @Test
    void analysisScopeRejectsForeignPositiveAssessmentWithoutAScore() {
        var scope = new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY);
        var base = input(Map.of()).withScope(scope, null);
        var supported = new RequirementNodeAssessment("Frozen requirement",
                List.of(RequirementNodeAssessment.SubjectKind.PROCESS), List.of(RequirementNodeAssessment.RequirementKind.FUNCTIONAL),
                RequirementNodeAssessment.ContributionType.SUPPORTS, RequirementNodeAssessment.EvidenceStatus.SUPPORTED,
                RequirementNodeAssessment.EvidenceBasis.EXPLICIT, RequirementNodeAssessment.Necessity.REQUIRED,
                RequirementNodeAssessment.Scope.DELIVERY, "", "Captured supporting evidence.", "");
        assertThat(generate(base.withNodeAssessments(Map.of("BP-looking", List.of(supported))), null).nodeAssessments())
                .containsOnlyKeys("BP-looking");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> generate(base.withNodeAssessments(Map.of("CP-looking", List.of(supported))), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> generate(input(Map.of()).withNodeAssessments(Map.of("unknown", List.of(supported))), null));
    }

    private static RequirementNodeAssessment unresolved(String question) {
        return new RequirementNodeAssessment("Frozen requirement", List.of(RequirementNodeAssessment.SubjectKind.PROCESS),
                List.of(RequirementNodeAssessment.RequirementKind.FUNCTIONAL), RequirementNodeAssessment.ContributionType.DIRECT_SUBJECT,
                RequirementNodeAssessment.EvidenceStatus.UNRESOLVED, RequirementNodeAssessment.EvidenceBasis.UNSPECIFIED,
                RequirementNodeAssessment.Necessity.UNDETERMINED, RequirementNodeAssessment.Scope.UNSPECIFIED,
                "", "More detail is needed.", question);
    }

    @Test
    void completedSelectedAnalysisDoesNotRequireUnselectedRoots() {
        var report = generate(input(Map.of("CP", 80d, "BP-looking", 80d, "CP-zero", 0d))
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
        var input = input(Map.of("CP", 80d, "BP-looking", 80d, "CP-zero", 0d, "BP", 95d, "CP-looking", 95d));
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
        var scores = Map.of("CP", 80d, "BP-looking", 80d, "CP-zero", 0d);
        var coverage = AnalysisCoverage.derive(tree, scores, scores, Map.of("BP", "FAILED: unavailable"));
        var report = generate(input(scores).withScope(AnalysisScope.full(), coverage), compact("CP"));
        assertThat(report.status()).isEqualTo(ReportStatus.DRAFT_INCOMPLETE);
        assertThat(report.scope().selectionComplete()).isTrue();
        assertThat(report.scope().analysisComplete()).isFalse();
        assertThat(report.scope().analysisCoverage()).isEqualTo(coverage);
    }

    @Test
    void zeroRootAndMissingAlternativesSurviveWithoutPositiveChapter() {
        var rejected = generate(input(Map.of("CP", 0d, "BP", 0d)), compact("CP"));
        assertThat(rejected.scope().decisionTree().rows()).extracting(DecisionTreeRow::code).containsExactly("CP");
        assertThat(rejected.scope().decisionTree().rows().getFirst().disposition()).isEqualTo(Disposition.REJECTED);
        assertThat(rejected.scope().recordedReasons()).containsOnlyKeys("CP").containsEntry("CP", "Saved reason for CP");
        assertThat(new tools.jackson.databind.json.JsonMapper().valueToTree(rejected).path("scope").path("recordedReasons").path("CP").asText())
                .isEqualTo("Saved reason for CP");
        var partial = generate(input(Map.of("CP", 80d, "BP-looking", 80d, "BP", 0d)), compact("CP"));
        assertThat(partial.scope().decisionTree().rows()).extracting(DecisionTreeRow::code)
                .containsExactly("CP", "BP-looking", "CP-zero");
        assertThat(partial.scope().decisionTree().rows().getLast().score()).isNull();
        assertThat(partial.scope().selectionComplete()).isFalse();
    }

    @Test
    void rejectsUnknownRootsAndRootsOutsideRecordedAnalysisScope() {
        var input = input(Map.of("CP", 80d, "BP-looking", 80d, "CP-zero", 0d))
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
        var input = input(Map.of("CP", 0d, "BP", 100d, "CP-looking", 100d))
                .withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL), null);
        assertThatIllegalArgumentException().isThrownBy(() -> generate(input, null));
    }

    @Test
    void sourceFingerprintDistinguishesTaxonomiesOnlyFromFullAnalysis() {
        var input = input(Map.of("CP", 80d, "BP-looking", 80d, "CP-zero", 0d));
        var taxonomyOnly = generate(input.withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY), null), null);
        var full = generate(input.withScope(new AnalysisScope(Set.of("CP"), AnalysisMode.FULL), null), null);
        assertThat(taxonomyOnly.metadata().analysisSnapshotFingerprintSha256())
                .isNotEqualTo(full.metadata().analysisSnapshotFingerprintSha256());
    }

    @Test
    void scoreAdaptationKeepsSelectedRawEvidenceAndRemainsIdempotent() {
        var input = input(Map.of("CP", 80d, "BP-looking", 80d, "CP-zero", 0d, "BP", 95d, "CP-looking", 95d));
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
        var scores = Map.of("CP", 80d, "BP-looking", 64d, "CP-zero", 0d);
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

    @Test
    void oldSnapshotCannotAcquireCurrentResponsibilitiesEvenWhenExplicitlyRequested() {
        var lookup = mock(TaxonomyResponsibilityLookup.class);
        var service = service();
        service.setResponsibilityLookup(lookup);
        var source = input(Map.of("CP", 80d, "BP-looking", 80d, "CP-zero", 0d));
        var options = new DecisionReportOptions(null, null, null, null, null, true);
        var report = service.generate(source, context, null, Locale.ENGLISH, options, null, true);
        assertThat(report.scope().decisionTree().responsibilitiesByCode()).isEmpty();
        verifyNoInteractions(lookup);
    }

    private DecisionAnalysisInput input(Map<String, Double> scores) {
        var reasons = new LinkedHashMap<String, String>();
        scores.forEach((code, score) -> reasons.put(code, "Saved reason for " + code));
        var provenance = new DecisionRationaleReportService.AnalysisSnapshotProvenance("snapshot", 1L, 2L,
                3L, 1, java.time.Instant.EPOCH, "author", "mock-model", TaxonomyDataFingerprint.sha256(tree), "prompt");
        return new DecisionAnalysisInput("Frozen requirement", scores, reasons, "MOCK", "SUCCESS",
                List.of(), tree, provenance);
    }

    private DecisionRationaleReport generate(DecisionAnalysisInput input, DecisionReportOptions options) {
        return service().generate(input, context, null, Locale.ENGLISH, options);
    }

    private DecisionRationaleReportService service() {
        var catalogue = mock(TaxonomyCatalogueMetadataService.class);
        var build = mock(DecisionReportBuildMetadataService.class);
        when(catalogue.getMetadata()).thenReturn(new TaxonomyCatalogueMetadataService.CatalogueMetadata(
                "catalogue.xlsx", "v1", "hash", "source", null, null, null, null));
        when(build.current()).thenReturn(new DecisionReportBuildMetadataService.BuildMetadata("1", "commit"));
        return new DecisionRationaleReportService(mock(TaxonomyService.class), catalogue, build, "UTC");
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
