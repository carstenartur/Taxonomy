package com.taxonomy.versioning.controller;

import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleScoreSemanticsAdapter;
import com.taxonomy.architecture.report.ReportRendererRegistry;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.AnalysisScoreSemantics;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.versioning.controller.DecisionRationaleReportController.DecisionReportRequest;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DecisionRationaleReportControllerScoreSemanticsTest {

    @Test
    void derivesEffectiveScoresFromBoundedRawEvidenceAndIgnoresInjectedDerivedMaps() {
        TaxonomyService taxonomyService = mock(TaxonomyService.class);
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto family = node("IP-F", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto product = node("IP-P", "IP-F", "PRODUCT");
        family.setChildren(List.of(product));
        root.setChildren(List.of(family));
        when(taxonomyService.getFullTree()).thenReturn(List.of(root));

        DecisionRationaleReportController controller = controller(taxonomyService);

        AnalysisScoreDetail injectedProduct = new AnalysisScoreDetail(
                "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                100, 100, "IP-F", 100);
        AnalysisScoreDetail injectedExtra = new AnalysisScoreDetail(
                "EXTRA", AnalysisScoreKind.ROOT_RELEVANCE,
                100, 100, null, null);
        DecisionReportRequest request = new DecisionReportRequest(
                Map.of("IP", 100, "IP-F", 40, "IP-P", 100),
                Map.of("IP", 100, "IP-F", 40, "IP-P", 80, "EXTRA", 100),
                Map.of("IP", 100, "IP-F", 40, "IP-P", 100, "EXTRA", 100),
                Map.of("IP-P", injectedProduct, "EXTRA", injectedExtra),
                Map.of("IP-P", 100, "EXTRA", 100),
                AnalysisScoreSemantics.CURRENT_VERSION,
                Map.of(),
                "requirement",
                "MOCK",
                "SUCCESS",
                List.of(),
                List.of(),
                "en");

        Boolean valid = ReflectionTestUtils.invokeMethod(controller, "isValid", request);
        assertThat(valid).isTrue();

        AnalysisScoreSemantics.Derived derived = ReflectionTestUtils.invokeMethod(
                controller, "resolveScoreSemantics", request);

        assertThat(derived).isNotNull();
        assertThat(derived.effectiveScores())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("IP", 100, "IP-F", 40, "IP-P", 32))
                .doesNotContainKey("EXTRA");
        assertThat(derived.scoreDetails().get("IP-P").rawScore()).isEqualTo(80);
        assertThat(derived.scoreDetails().get("IP-P").parentScore()).isEqualTo(40);
        assertThat(derived.scoreDetails().get("IP-P").kind())
                .isEqualTo(AnalysisScoreKind.PRODUCT_SUITABILITY);
    }

    @Test
    void rejectsNonCanonicalScoreKeysBeforeSemanticNormalization() {
        DecisionRationaleReportController controller = controller(null);
        Map<String, Integer> colliding = new LinkedHashMap<>();
        colliding.put("IP", 40);
        colliding.put("IP ", 60);

        Boolean canonical = ReflectionTestUtils.invokeMethod(
                controller, "validScoreMap", Map.of("IP", 40));
        Boolean leadingWhitespace = ReflectionTestUtils.invokeMethod(
                controller, "validScoreMap", Map.of(" IP", 40));
        Boolean trailingWhitespace = ReflectionTestUtils.invokeMethod(
                controller, "validScoreMap", Map.of("IP ", 40));
        Boolean stripCollision = ReflectionTestUtils.invokeMethod(
                controller, "validScoreMap", colliding);

        assertThat(canonical).isTrue();
        assertThat(leadingWhitespace).isFalse();
        assertThat(trailingWhitespace).isFalse();
        assertThat(stripCollision).isFalse();
    }

    @Test
    void rejectsIncompleteRawEvidenceInEveryFormatBeforeAnyReportSideEffects() {
        DecisionRationaleReportService reportService = mock(DecisionRationaleReportService.class);
        ReportRendererRegistry registry = mock(ReportRendererRegistry.class);
        RepositoryStateService repositoryState = mock(RepositoryStateService.class);
        WorkspaceResolver workspaceResolver = mock(WorkspaceResolver.class);
        TaxonomyService taxonomy = mock(TaxonomyService.class);
        DecisionRationaleReportController controller = new DecisionRationaleReportController(
                reportService, registry, repositoryState, workspaceResolver,
                new DecisionRationaleScoreSemanticsAdapter(), taxonomy);
        Map<String, Integer> scores = Map.of("IP", 100, "IP-2072", 40, "IP-1286", 32);
        List<Map<String, Integer>> incompleteRawMaps = List.of(
                Map.of("IP", 100, "IP-2072", 40),
                Map.of("IP", 100, "IP-1286", 80),
                Map.of("IP-2072", 40, "IP-1286", 80),
                Map.of(),
                Map.of("UA", 0),
                Map.of("IP", 100, "IP-2072", 40, "UA", 80));

        for (Map<String, Integer> raw : incompleteRawMaps) {
            assertRejectedInEveryFormat(controller, request(scores, raw));
        }
        verifyNoInteractions(reportService, registry, repositoryState, workspaceResolver, taxonomy);
    }

    @Test
    void rejectsInvalidRawValuesEvenWhenEverySelectedCodeIsPresent() {
        DecisionRationaleReportController controller = controller(null);
        Map<String, Integer> scores = Map.of("IP", 100, "IP-2072", 40, "IP-1286", 32);
        for (Integer invalidValue : new Integer[] {null, -1, 101}) {
            Map<String, Integer> raw = new LinkedHashMap<>(scores);
            raw.put("IP-1286", invalidValue);
            assertRejectedInEveryFormat(controller, request(scores, raw));
        }
    }

    @Test
    void acceptsLegacyRequestsAndCompleteRawEvidenceIncludingExplicitZero() {
        DecisionRationaleReportController controller = controller(null);
        Map<String, Integer> raw = Map.of("IP", 100, "IP-2072", 40, "IP-1286", 80);
        DecisionReportRequest legacy = new DecisionReportRequest(
                raw, Map.of(), "requirement", "MOCK", "SUCCESS", List.of(), List.of(), "en");
        Boolean legacyValid = ReflectionTestUtils.invokeMethod(controller, "isValid", legacy);
        Boolean nullRawValid = ReflectionTestUtils.invokeMethod(
                controller, "isValid", request(raw, null));
        Map<String, Integer> scores = Map.of("IP", 100, "IP-2072", 40, "IP-1286", 0);
        Boolean zeroValid = ReflectionTestUtils.invokeMethod(
                controller, "isValid", request(scores, scores));
        Map<String, Integer> withExtra = new LinkedHashMap<>(raw);
        withExtra.put("UA", 0);
        Boolean extraValid = ReflectionTestUtils.invokeMethod(
                controller, "isValid", request(scores, withExtra));

        assertThat(legacyValid).isTrue();
        assertThat(nullRawValid).isTrue();
        assertThat(zeroValid).isTrue();
        assertThat(extraValid).isTrue();
    }

    private void assertRejectedInEveryFormat(
            DecisionRationaleReportController controller, DecisionReportRequest request) {
        for (var response : List.of(controller.exportJson(request),
                controller.exportHtml(request), controller.exportDocx(request))) {
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).isNull();
            assertThat(response.getHeaders().getFirst("Content-Disposition")).isNull();
        }
    }

    private DecisionReportRequest request(
            Map<String, Integer> scores, Map<String, Integer> rawScores) {
        return new DecisionReportRequest(scores, rawScores, null, null, null, null,
                Map.of(), "requirement", "MOCK", "SUCCESS", List.of(), List.of(), "en");
    }

    private DecisionRationaleReportController controller(TaxonomyService taxonomyService) {
        return new DecisionRationaleReportController(
                mock(DecisionRationaleReportService.class),
                mock(ReportRendererRegistry.class),
                mock(RepositoryStateService.class),
                mock(WorkspaceResolver.class),
                new DecisionRationaleScoreSemanticsAdapter(),
                taxonomyService);
    }

    private TaxonomyNodeDto node(String code, String parentCode, String role) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parentCode);
        node.setAnalysisRole(role);
        return node;
    }
}
