package com.taxonomy.composition.report;

import com.taxonomy.architecture.decision.DecisionRationaleReportService;
import com.taxonomy.architecture.decision.DecisionRationaleScoreSemanticsAdapter;
import com.taxonomy.reporting.render.document.ReportRendererRegistry;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.AnalysisScoreSemantics;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.composition.report.DecisionRationaleReportController.DecisionReportRequest;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(taxonomyService.getFingerprintTree()).thenReturn(List.of(root));

        DecisionRationaleReportController controller = controller(taxonomyService);

        AnalysisScoreDetail injectedProduct = new AnalysisScoreDetail(
                "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                100, 100, "IP-F", 100);
        AnalysisScoreDetail injectedExtra = new AnalysisScoreDetail(
                "EXTRA", AnalysisScoreKind.ROOT_RELEVANCE,
                100, 100, null, null);
        DecisionReportRequest request = new DecisionReportRequest(
                Map.of("IP", 100d, "IP-F", 40d, "IP-P", 100d),
                Map.of("IP", 100d, "IP-F", 40d, "IP-P", 80d, "EXTRA", 100d),
                Map.of("IP", 100d, "IP-F", 40d, "IP-P", 100d, "EXTRA", 100d),
                Map.of("IP-P", injectedProduct, "EXTRA", injectedExtra),
                Map.of("IP-P", 100d, "EXTRA", 100d),
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
                        Map.of("IP", 100d, "IP-F", 40d, "IP-P", 32d))
                .doesNotContainKey("EXTRA");
        assertThat(derived.scoreDetails().get("IP-P").rawScore()).isEqualTo(80);
        assertThat(derived.scoreDetails().get("IP-P").parentScore()).isEqualTo(40);
        assertThat(derived.scoreDetails().get("IP-P").kind())
                .isEqualTo(AnalysisScoreKind.PRODUCT_SUITABILITY);
        verify(taxonomyService).getFingerprintTree();
        verify(taxonomyService, never()).getFullTree();
    }

    @Test
    void rejectsNonCanonicalScoreKeysBeforeSemanticNormalization() {
        DecisionRationaleReportController controller = controller(null);
        Map<String, Double> colliding = new LinkedHashMap<>();
        colliding.put("IP", 40d);
        colliding.put("IP ", 60d);

        Boolean canonical = ReflectionTestUtils.invokeMethod(
                controller, "validScoreMap", Map.of("IP", 40d));
        Boolean leadingWhitespace = ReflectionTestUtils.invokeMethod(
                controller, "validScoreMap", Map.of(" IP", 40d));
        Boolean trailingWhitespace = ReflectionTestUtils.invokeMethod(
                controller, "validScoreMap", Map.of("IP ", 40d));
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
        Map<String, Double> scores = Map.of("IP", 100d, "IP-2072", 40d, "IP-1286", 32d);
        List<Map<String, Double>> incompleteRawMaps = List.of(
                Map.of("IP", 100d, "IP-2072", 40d),
                Map.of("IP", 100d, "IP-1286", 80d),
                Map.of("IP-2072", 40d, "IP-1286", 80d),
                Map.of(),
                Map.of("UA", 0d),
                Map.of("IP", 100d, "IP-2072", 40d, "UA", 80d));

        for (Map<String, Double> raw : incompleteRawMaps) {
            assertRejectedInEveryFormat(controller, request(scores, raw));
        }
        verifyNoInteractions(reportService, registry, repositoryState, workspaceResolver, taxonomy);
    }

    @Test
    void rejectsInvalidRawValuesEvenWhenEverySelectedCodeIsPresent() {
        DecisionRationaleReportController controller = controller(null);
        Map<String, Double> scores = Map.of("IP", 100d, "IP-2072", 40d, "IP-1286", 32d);
        for (Double invalidValue : new Double[] {null, -1d, 101d, Double.NaN, Double.POSITIVE_INFINITY}) {
            Map<String, Double> raw = new LinkedHashMap<>(scores);
            raw.put("IP-1286", invalidValue);
            assertRejectedInEveryFormat(controller, request(scores, raw));
        }
    }

    @Test
    void acceptsLegacyRequestsAndCompleteRawEvidenceIncludingExplicitZero() {
        DecisionRationaleReportController controller = controller(null);
        Map<String, Double> raw = Map.of("IP", 100d, "IP-2072", 40d, "IP-1286", 80d);
        DecisionReportRequest legacy = new DecisionReportRequest(
                raw, Map.of(), "requirement", "MOCK", "SUCCESS", List.of(), List.of(), "en");
        Boolean legacyValid = ReflectionTestUtils.invokeMethod(controller, "isValid", legacy);
        Boolean nullRawValid = ReflectionTestUtils.invokeMethod(
                controller, "isValid", request(raw, null));
        Map<String, Double> scores = Map.of("IP", 100d, "IP-2072", 40d, "IP-1286", 0d);
        Boolean zeroValid = ReflectionTestUtils.invokeMethod(
                controller, "isValid", request(scores, scores));
        Map<String, Double> withExtra = new LinkedHashMap<>(raw);
        withExtra.put("UA", 0d);
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
            Map<String, Double> scores, Map<String, Double> rawScores) {
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
