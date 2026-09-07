package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void taxonomyFingerprintRejectsReusedNodeInstances() {
        TaxonomyNodeDto shared = node("IP-P", null, "PRODUCT");
        TaxonomyNodeDto firstFamily = node("IP-F1", "IP", "PRODUCT_FAMILY");
        firstFamily.setChildren(List.of(shared));
        TaxonomyNodeDto secondFamily = node("IP-F2", "IP", "PRODUCT_FAMILY");
        secondFamily.setChildren(List.of(shared));
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        root.setChildren(List.of(firstFamily, secondFamily));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxonomyDataFingerprint.sha256(List.of(root)));

        assertTrue(failure.getMessage().contains("reuses node IP-P"));
    }

    @Test
    void taxonomyFingerprintRejectsCyclesAndDuplicateCodes() {
        TaxonomyNodeDto cycleRoot = node("IP", null, "CATEGORY");
        cycleRoot.setChildren(List.of(cycleRoot));
        IllegalArgumentException cycleFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxonomyDataFingerprint.sha256(List.of(cycleRoot)));
        assertTrue(cycleFailure.getMessage().contains("cycle at node IP"));

        TaxonomyNodeDto duplicateRoot = node("IP", null, "CATEGORY");
        duplicateRoot.setChildren(List.of(
                node("IP-DUP", "IP", "CATEGORY"),
                node("IP-DUP", "IP", "CATEGORY")));
        IllegalArgumentException duplicateFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TaxonomyDataFingerprint.sha256(List.of(duplicateRoot)));
        assertTrue(duplicateFailure.getMessage().contains("duplicate node code IP-DUP"));
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
