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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

        DecisionRationaleReportController controller = new DecisionRationaleReportController(
                mock(DecisionRationaleReportService.class),
                mock(ReportRendererRegistry.class),
                mock(RepositoryStateService.class),
                mock(WorkspaceResolver.class),
                new DecisionRationaleScoreSemanticsAdapter(),
                taxonomyService);

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

    private TaxonomyNodeDto node(String code, String parentCode, String role) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parentCode);
        node.setAnalysisRole(role);
        return node;
    }
}
