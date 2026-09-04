package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.AnalysisScoreKind;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.portfolio.dto.PortfolioDtos.ScoreChange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisScoreDiffTest {

    @Test
    void rawSuitabilityChangeIsVisibleWhenRoundedEffectiveRelevanceStaysEqual() {
        AnalysisResult older = productAnalysis(40, 80, "IP-F");
        AnalysisResult newer = productAnalysis(40, 81, "IP-F");

        ScoreChange change = AnalysisScoreDiff.between(older, newer).get("IP-P");

        assertThat(change).isNotNull();
        assertThat(change.oldScore()).isEqualTo(32);
        assertThat(change.newScore()).isEqualTo(32);
        assertThat(change.oldRawScore()).isEqualTo(80);
        assertThat(change.newRawScore()).isEqualTo(81);
        assertThat(change.oldKind()).isEqualTo(AnalysisScoreKind.PRODUCT_SUITABILITY);
        assertThat(change.newKind()).isEqualTo(AnalysisScoreKind.PRODUCT_SUITABILITY);
    }

    @Test
    void productParentChangeIsVisibleWhenAllNumericValuesStayEqual() {
        AnalysisResult older = productAnalysisWithTwoFamilies("IP-F1");
        AnalysisResult newer = productAnalysisWithTwoFamilies("IP-F2");

        ScoreChange change = AnalysisScoreDiff.between(older, newer).get("IP-P");

        assertThat(change).isNotNull();
        assertThat(change.oldScore()).isEqualTo(change.newScore()).isEqualTo(32);
        assertThat(change.oldRawScore()).isEqualTo(change.newRawScore()).isEqualTo(80);
        assertThat(change.oldParentCode()).isEqualTo("IP-F1");
        assertThat(change.newParentCode()).isEqualTo("IP-F2");
        assertThat(change.oldParentScore()).isEqualTo(change.newParentScore()).isEqualTo(40);
    }

    private AnalysisResult productAnalysis(
            int familyScore,
            int suitability,
            String familyCode) {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto family = node(familyCode, "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto product = node("IP-P", familyCode, "PRODUCT");
        family.setChildren(List.of(product));
        root.setChildren(List.of(family));
        return new AnalysisResult(
                Map.of("IP", 100, familyCode, familyScore, "IP-P", suitability),
                List.of(root));
    }

    private AnalysisResult productAnalysisWithTwoFamilies(String productParent) {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto firstFamily = node("IP-F1", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto secondFamily = node("IP-F2", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto product = node("IP-P", productParent, "PRODUCT");
        if ("IP-F1".equals(productParent)) {
            firstFamily.setChildren(List.of(product));
        } else {
            secondFamily.setChildren(List.of(product));
        }
        root.setChildren(List.of(firstFamily, secondFamily));
        return new AnalysisResult(
                Map.of("IP", 100, "IP-F1", 40, "IP-F2", 40, "IP-P", 80),
                List.of(root));
    }

    private TaxonomyNodeDto node(String code, String parentCode, String role) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parentCode);
        node.setAnalysisRole(role);
        return node;
    }
}
