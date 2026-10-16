package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisScoreSemanticsTest {

    @Test
    void productSuitabilityIsRetainedWhileGenericScoresUseFamilyWeightedRelevance() {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto family = node("IP-F", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto first = node("IP-P1", "IP-F", "PRODUCT");
        TaxonomyNodeDto second = node("IP-P2", "IP-F", "PRODUCT");
        TaxonomyNodeDto ordinaryLeaf = node("IP-C", "IP", "CATEGORY");
        family.setChildren(List.of(first, second));
        root.setChildren(List.of(family, ordinaryLeaf));

        AnalysisResult result = new AnalysisResult(
                Map.of("IP", 100d, "IP-F", 40d, "IP-P1", 80d, "IP-P2", 70d, "IP-C", 60d),
                List.of(root));

        assertEquals(80d, result.getRawScores().get("IP-P1"));
        assertEquals(80d, result.getProductSuitabilityScores().get("IP-P1"));
        assertEquals(32d, result.getScores().get("IP-P1"));
        assertEquals(28d, result.getEffectiveScores().get("IP-P2"));
        assertEquals(60d, result.getScores().get("IP-C"));
        assertEquals(AnalysisScoreKind.PRODUCT_SUITABILITY,
                result.getScoreDetails().get("IP-P1").kind());
        assertEquals(40d, result.getScoreDetails().get("IP-P1").parentScore());
        assertEquals(AnalysisScoreKind.HIERARCHICAL_RELEVANCE,
                result.getScoreDetails().get("IP-F").kind());
        assertTrue(result.getScoreSemanticsWarnings().isEmpty());
    }

    @Test
    void perfectProductOnWeakFamilyCannotOutrankStrongOrdinaryLeaf() {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto weakFamily = node("IP-F", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto product = node("IP-P", "IP-F", "PRODUCT");
        TaxonomyNodeDto ordinaryLeaf = node("IP-C", "IP", "CATEGORY");
        weakFamily.setChildren(List.of(product));
        root.setChildren(List.of(weakFamily, ordinaryLeaf));

        AnalysisResult result = new AnalysisResult(
                Map.of("IP", 100d, "IP-F", 10d, "IP-P", 100d, "IP-C", 80d),
                List.of(root));

        assertEquals(100d, result.getProductSuitabilityScores().get("IP-P"));
        assertEquals(10d, result.getScores().get("IP-P"));
        assertTrue(result.getScores().get("IP-P") < result.getScores().get("IP-C"));
    }

    @Test
    void missingProductFamilyScoreFailsClosedInsteadOfPromotingSuitability() {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto family = node("IP-F", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto product = node("IP-P", "IP-F", "PRODUCT");
        family.setChildren(List.of(product));
        root.setChildren(List.of(family));

        AnalysisResult result = new AnalysisResult(Map.of("IP-P", 95d), List.of(root));

        assertEquals(95d, result.getRawScores().get("IP-P"));
        assertEquals(0d, result.getScores().get("IP-P"));
        assertFalse(result.getScoreSemanticsWarnings().isEmpty());
    }

    @Test
    void settingFrozenTreeAfterAnEarlyReadRecomputesScoreSemantics() {
        AnalysisResult result = new AnalysisResult();
        result.setScores(Map.of("IP-F", 40d, "IP-P", 80d));

        assertEquals(80d, result.getScores().get("IP-P"));
        assertEquals(AnalysisScoreKind.HIERARCHICAL_RELEVANCE,
                result.getScoreDetails().get("IP-P").kind());

        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto family = node("IP-F", "IP", "PRODUCT_FAMILY");
        TaxonomyNodeDto product = node("IP-P", "IP-F", "PRODUCT");
        family.setChildren(List.of(product));
        root.setChildren(List.of(family));
        result.setTree(List.of(root));

        assertEquals(32d, result.getScores().get("IP-P"));
        assertEquals(80d, result.getProductSuitabilityScores().get("IP-P"));
        assertEquals(AnalysisScoreKind.PRODUCT_SUITABILITY,
                result.getScoreDetails().get("IP-P").kind());
    }

    @Test
    void duplicateTaxonomyCodesFailClosed() {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto first = node("IP-DUP", "IP", "CATEGORY");
        TaxonomyNodeDto second = node("IP-DUP", "IP", "PRODUCT");
        root.setChildren(List.of(first, second));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisScoreSemantics.derive(Map.of("IP-DUP", 80d), List.of(root)));

        assertTrue(failure.getMessage().contains("duplicate node code IP-DUP"));
    }

    @Test
    void canonicalScoreKeyCollisionsFailClosed() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("IP", 40d);
        scores.put("IP ", 60d);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> AnalysisScoreSemantics.derive(scores, List.of()));

        assertTrue(failure.getMessage().contains("canonical node code IP"));
    }

    @Test
    void canonicalScoreKeysAreSortedAfterNormalization() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put(" Z", 20d);
        scores.put("A", 10d);
        scores.put(" M ", 15d);

        AnalysisResult result = new AnalysisResult();
        result.setScores(scores);

        assertEquals(List.of("A", "M", "Z"),
                result.getRawScores().keySet().stream().toList());
    }

    @Test
    void exactlyOneHundredWarningsRetainAllWarningsWithoutSuppressionMarker() {
        Map<String, Double> scores = unknownScores(100);

        AnalysisScoreSemantics.Derived derived =
                AnalysisScoreSemantics.derive(scores, List.of());

        assertEquals(100, derived.warnings().size());
        assertTrue(derived.warnings().stream()
                .noneMatch(warning -> warning.contains("were suppressed")));
        assertTrue(derived.warnings().get(99).contains("UNKNOWN-099"));
    }

    @Test
    void moreThanOneHundredWarningsKeepOneHundredWarningsAndOneSuppressionMarker() {
        Map<String, Double> scores = unknownScores(150);

        AnalysisScoreSemantics.Derived derived =
                AnalysisScoreSemantics.derive(scores, List.of());

        assertEquals(150, derived.effectiveScores().size());
        assertEquals(101, derived.warnings().size());
        assertTrue(derived.warnings().get(99).contains("UNKNOWN-099"));
        assertEquals("Additional score-semantics warnings were suppressed.",
                derived.warnings().get(100));
        assertEquals(1L, derived.warnings().stream()
                .filter(warning -> warning.contains("were suppressed"))
                .count());
    }

    @Test
    void rawScoreKeysAreCanonicalizedOnceAndRemainComplete() {
        Map<String, Double> rawScores = new LinkedHashMap<>();
        rawScores.put(" NODE ", 80d);
        rawScores.put("NULL", null);
        rawScores.put(" ", 50d);

        AnalysisResult result = new AnalysisResult();
        result.setScores(rawScores);

        assertEquals(Map.of("NODE", 80d), result.getRawScores());
        Map<String, AnalysisScoreDetail> firstRead = result.getScoreDetails();
        Map<String, AnalysisScoreDetail> secondRead = result.getScoreDetails();
        assertSame(firstRead, secondRead);
        assertEquals(result.getRawScores().keySet(), firstRead.keySet());
    }

    @Test
    void mismatchedScoreDetailNodeCodeForcesRecomputation() {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        AnalysisResult result = new AnalysisResult(Map.of("IP", 100d), List.of(root));
        AnalysisScoreDetail forged = new AnalysisScoreDetail(
                "OTHER", AnalysisScoreKind.ROOT_RELEVANCE,
                100, 100, null, null);
        Map<String, AnalysisScoreDetail> forgedDetails = Map.of("IP", forged);
        result.setScoreDetails(forgedDetails);
        result.setEffectiveScores(Map.of("IP", 100d));
        result.setScoreSemanticsVersion(AnalysisScoreSemantics.CURRENT_VERSION);
        result.setScoreSemanticsFingerprintSha256(
                AnalysisScoreSemanticsFingerprint.sha256(forgedDetails));

        AnalysisScoreDetail repaired = result.getScoreDetails().get("IP");

        assertEquals("IP", repaired.nodeCode());
        assertEquals(AnalysisScoreKind.ROOT_RELEVANCE, repaired.kind());
        assertEquals(result.getScoreSemanticsFingerprintSha256(),
                AnalysisScoreSemanticsFingerprint.sha256(result.getScoreDetails()));
    }

    @Test
    void explicitRawScoresWinRegardlessOfLegacyJsonPropertyOrder() {
        AnalysisResult result = new AnalysisResult();
        result.setRawScores(Map.of("P", 80d));
        result.setScores(Map.of("P", 32d));

        assertEquals(80d, result.getRawScores().get("P"));
    }

    private Map<String, Double> unknownScores(int count) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            scores.put("UNKNOWN-" + String.format("%03d", index), 50d);
        }
        return scores;
    }

    private TaxonomyNodeDto node(String code, String parentCode, String role) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parentCode);
        node.setAnalysisRole(role);
        return node;
    }
}
