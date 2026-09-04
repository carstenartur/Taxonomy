package com.taxonomy.architecture.decision;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisScoreDetail;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionRationaleProductBudgetWarningTest {

    @Test
    void independentProductSuitabilityDoesNotCreateFalseHierarchyBudgetWarning() {
        TaxonomyService taxonomyService = mock(TaxonomyService.class);
        TaxonomyNode root = node("IP", null, 0);
        TaxonomyNode family = node("IP-F", "IP", 1);
        TaxonomyNode product = node("IP-P", "IP-F", 2);
        when(taxonomyService.getRootNodes()).thenReturn(List.of(root));
        when(taxonomyService.getChildrenMap()).thenReturn(Map.of(
                "IP", List.of(family),
                "IP-F", List.of(product)));

        TaxonomyNodeDto rootDto = dto("IP", null, "CATEGORY", 0);
        TaxonomyNodeDto familyDto = dto("IP-F", "IP", "PRODUCT_FAMILY", 1);
        TaxonomyNodeDto productDto = dto("IP-P", "IP-F", "PRODUCT", 2);
        familyDto.setChildren(List.of(productDto));
        rootDto.setChildren(List.of(familyDto));
        when(taxonomyService.getFullTree()).thenReturn(List.of(rootDto));

        TaxonomyCatalogueMetadataService catalogue =
                mock(TaxonomyCatalogueMetadataService.class);
        when(catalogue.getMetadata()).thenReturn(
                new TaxonomyCatalogueMetadataService.CatalogueMetadata(
                        "catalogue.xlsx", "test", "data", "test",
                        "base", "overlay", "mapping", "overlay-sha"));
        DecisionReportBuildMetadataService build =
                mock(DecisionReportBuildMetadataService.class);
        when(build.current()).thenReturn(
                new DecisionReportBuildMetadataService.BuildMetadata("test", "commit"));

        DecisionRationaleReportService service = new DecisionRationaleReportService(
                taxonomyService, catalogue, build, "Europe/Berlin");
        Map<String, Integer> scores = Map.of(
                "IP", 40, "IP-F", 40, "IP-P", 32);
        Map<String, AnalysisScoreDetail> details = Map.of(
                "IP", new AnalysisScoreDetail(
                        "IP", AnalysisScoreKind.ROOT_RELEVANCE,
                        40, 40, null, null),
                "IP-F", new AnalysisScoreDetail(
                        "IP-F", AnalysisScoreKind.HIERARCHICAL_RELEVANCE,
                        40, 40, "IP", 40),
                "IP-P", new AnalysisScoreDetail(
                        "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                        80, 32, "IP-F", 40));
        DecisionRationaleReportService.DecisionAnalysisInput input =
                new DecisionRationaleReportService.DecisionAnalysisInput(
                        "requirement",
                        scores,
                        Map.of("IP", "root", "IP-F", "family", "IP-P", "product"),
                        "MOCK",
                        "SUCCESS",
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        details);

        DecisionRationaleReport report = service.generate(
                input, WorkspaceContext.SHARED, null, Locale.ENGLISH);

        assertThat(report.warnings()).noneMatch(warning ->
                warning.contains("sum of fully evaluated direct children"));
    }

    private TaxonomyNode node(String code, String parentCode, int level) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(code);
        node.setDescriptionEn(code);
        node.setParentCode(parentCode);
        node.setTaxonomyRoot("IP");
        node.setLevel(level);
        return node;
    }

    private TaxonomyNodeDto dto(
            String code, String parentCode, String role, int level) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setNameEn(code);
        node.setDescriptionEn(code);
        node.setParentCode(parentCode);
        node.setTaxonomyRoot("IP");
        node.setLevel(level);
        node.setAnalysisRole(role);
        return node;
    }
}
