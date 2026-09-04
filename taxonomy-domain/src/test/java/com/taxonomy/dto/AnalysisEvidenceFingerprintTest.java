package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisEvidenceFingerprintTest {

    @Test
    void typedScoreFingerprintIsOrderIndependentButIncludesRawAndParentEvidence() {
        AnalysisScoreDetail product80 = new AnalysisScoreDetail(
                "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                80, 32, "IP-F", 40);
        AnalysisScoreDetail family = new AnalysisScoreDetail(
                "IP-F", AnalysisScoreKind.HIERARCHICAL_RELEVANCE,
                40, 40, "IP", 100);

        Map<String, AnalysisScoreDetail> firstOrder = new LinkedHashMap<>();
        firstOrder.put("IP-P", product80);
        firstOrder.put("IP-F", family);
        Map<String, AnalysisScoreDetail> reverseOrder = new LinkedHashMap<>();
        reverseOrder.put("IP-F", family);
        reverseOrder.put("IP-P", product80);

        assertEquals(
                AnalysisScoreSemanticsFingerprint.sha256(firstOrder),
                AnalysisScoreSemanticsFingerprint.sha256(reverseOrder));

        AnalysisScoreDetail product81SameEffective = new AnalysisScoreDetail(
                "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                81, 32, "IP-F", 40);
        assertNotEquals(
                AnalysisScoreSemanticsFingerprint.sha256(firstOrder),
                AnalysisScoreSemanticsFingerprint.sha256(Map.of(
                        "IP-P", product81SameEffective,
                        "IP-F", family)));

        AnalysisScoreDetail movedProduct = new AnalysisScoreDetail(
                "IP-P", AnalysisScoreKind.PRODUCT_SUITABILITY,
                80, 32, "IP-OTHER", 40);
        assertNotEquals(
                AnalysisScoreSemanticsFingerprint.sha256(firstOrder),
                AnalysisScoreSemanticsFingerprint.sha256(Map.of(
                        "IP-P", movedProduct,
                        "IP-F", family)));
    }

    @Test
    void taxonomyFingerprintIncludesRoleAndParentButNotTraversalOrder() {
        TaxonomyNodeDto first = node("IP-P1", "IP-F", "PRODUCT");
        TaxonomyNodeDto second = node("IP-P2", "IP-F", "PRODUCT");
        TaxonomyNodeDto family = node("IP-F", "IP", "PRODUCT_FAMILY");
        family.setChildren(List.of(first, second));
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        root.setChildren(List.of(family));

        TaxonomyNodeDto reverseFamily = node("IP-F", "IP", "PRODUCT_FAMILY");
        reverseFamily.setChildren(List.of(
                node("IP-P2", "IP-F", "PRODUCT"),
                node("IP-P1", "IP-F", "PRODUCT")));
        TaxonomyNodeDto reverseRoot = node("IP", null, "CATEGORY");
        reverseRoot.setChildren(List.of(reverseFamily));

        List<TaxonomyNodeDto> tree = List.of(root);
        String baseline = TaxonomyDataFingerprint.sha256(tree);
        assertEquals(baseline, TaxonomyDataFingerprint.sha256(List.of(reverseRoot)));
        assertTrue(TaxonomyDataFingerprint.matchesRecorded(baseline, tree));
        assertTrue(TaxonomyDataFingerprint.matchesRecorded(
                TaxonomyDataFingerprint.legacySha256(tree), tree));

        TaxonomyNodeDto changedRoleFamily = node("IP-F", "IP", "PRODUCT_FAMILY");
        changedRoleFamily.setChildren(List.of(
                node("IP-P1", "IP-F", "CATEGORY"),
                node("IP-P2", "IP-F", "PRODUCT")));
        TaxonomyNodeDto changedRoleRoot = node("IP", null, "CATEGORY");
        changedRoleRoot.setChildren(List.of(changedRoleFamily));
        assertNotEquals(baseline, TaxonomyDataFingerprint.sha256(List.of(changedRoleRoot)));

        TaxonomyNodeDto changedParentFamily = node("IP-F", "IP-OTHER", "PRODUCT_FAMILY");
        changedParentFamily.setChildren(List.of(
                node("IP-P1", "IP-F", "PRODUCT"),
                node("IP-P2", "IP-F", "PRODUCT")));
        TaxonomyNodeDto changedParentRoot = node("IP", null, "CATEGORY");
        changedParentRoot.setChildren(List.of(changedParentFamily));
        assertNotEquals(baseline, TaxonomyDataFingerprint.sha256(List.of(changedParentRoot)));
    }

    private TaxonomyNodeDto node(String code, String parentCode, String role) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parentCode);
        node.setAnalysisRole(role);
        node.setNameEn(code + " name");
        node.setDescriptionEn(code + " description");
        node.setTaxonomyRoot("IP");
        node.setLevel(parentCode == null ? 0 : 1);
        return node;
    }
}
