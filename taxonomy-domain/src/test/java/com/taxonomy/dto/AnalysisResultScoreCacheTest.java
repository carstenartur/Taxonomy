package com.taxonomy.dto;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisResultScoreCacheTest {

    @Test
    void suitabilityCacheMustMatchTheExactProductEvidence() {
        Map<String, Integer> nullSuitability = new LinkedHashMap<>();
        nullSuitability.put("IP-P", null);
        List<Map<String, Integer>> staleCaches = List.of(
                Map.of("IP-P", 99),
                Map.of("IP-P", 80, "IP", 100),
                Map.of("IP-P", 80, "STALE", 55),
                Map.of(),
                nullSuitability);

        for (Map<String, Integer> staleCache : staleCaches) {
            AnalysisResult result = productResult();
            String fingerprint = result.getScoreSemanticsFingerprintSha256();
            result.setProductSuitabilityScores(staleCache);

            assertEquals(Map.of("IP-P", 80), result.getProductSuitabilityScores(),
                    "Must rebuild stale cache " + staleCache);
            assertEquals(32, result.getScores().get("IP-P"));
            assertEquals(fingerprint, result.getScoreSemanticsFingerprintSha256());
            assertSame(result.getScoreDetails(), result.getScoreDetails());
        }
    }

    @Test
    void mutationThroughTheSuitabilityGetterAlsoInvalidatesTheCache() {
        AnalysisResult result = productResult();
        result.getProductSuitabilityScores().put("IP-P", 99);

        assertEquals(32, result.getEffectiveScores().get("IP-P"));
        assertEquals(Map.of("IP-P", 80), result.getProductSuitabilityScores());
    }

    @Test
    void ordinaryRelevanceCannotBeInjectedAsProductEvidence() {
        AnalysisResult result = new AnalysisResult(
                Map.of("IP", 100), List.of(node("IP", null, "CATEGORY")));
        result.setProductSuitabilityScores(Map.of("IP", 100));

        assertTrue(result.getProductSuitabilityScores().isEmpty());
        assertEquals(100, result.getScores().get("IP"));
    }

    @Test
    void clearingRawEvidenceClearsEveryDerivedScoreView() {
        for (int mode = 0; mode < 4; mode++) {
            AnalysisResult result = productResult();
            result.setScoreSemanticsWarnings(List.of("Stale compatibility warning"));
            switch (mode) {
                case 0 -> result.setRawScores(Map.of());
                case 1 -> result.setRawScores(null);
                case 2 -> result.getRawScores().clear();
                case 3 -> result.setScores(Map.of());
                default -> throw new AssertionError("Unexpected clear mode");
            }

            assertEmptyScoreEvidence(result);
        }
    }

    @Test
    void absentRawEvidenceCannotRetainInjectedDerivedScores() {
        AnalysisResult result = new AnalysisResult();
        result.setProductSuitabilityScores(Map.of("IP-P", 99));
        result.setEffectiveScores(Map.of("IP-P", 99));
        result.setScoreDetails(productResult().getScoreDetails());
        result.setScoreSemanticsWarnings(List.of("Stale compatibility warning"));
        result.setScoreSemanticsFingerprintSha256("stale");

        assertEmptyScoreEvidence(result);
    }

    @Test
    void defaultRawEvidenceIsImmediatelyEmptyMutableAndInstanceLocal() {
        AnalysisResult first = new AnalysisResult();
        AnalysisResult second = new AnalysisResult();

        assertTrue(first.getRawScores().isEmpty());
        assertSame(first.getRawScores(), first.getRawScores());
        first.getRawScores().clear();
        first.getRawScores().put("IP", 40);
        assertEquals(Map.of("IP", 40), first.getRawScores());
        assertTrue(second.getRawScores().isEmpty());
        assertEquals(Map.of("IP", 40), first.getScores());
    }

    @Test
    void firstRawReadDoesNotOverrideLegacyInputPrecedence() {
        AnalysisResult result = new AnalysisResult();
        result.getRawScores().clear();
        result.setScores(Map.of("IP", 40));
        assertEquals(Map.of("IP", 40), result.getRawScores());

        result.setRawScores(Map.of("IP", 80));
        result.setScores(Map.of("IP", 32));
        assertEquals(Map.of("IP", 80), result.getRawScores());
    }

    @Test
    void nullInputsExposeEmptyRawEvidenceBeforeAnyDerivedRead() {
        AnalysisResult rawNull = new AnalysisResult();
        rawNull.setRawScores(null);
        AnalysisResult legacyNull = new AnalysisResult();
        legacyNull.setScores(null);

        for (AnalysisResult result : List.of(
                new AnalysisResult(), new AnalysisResult(null, List.of()), rawNull, legacyNull)) {
            assertTrue(result.getRawScores().isEmpty());
            result.getRawScores().clear();
            assertEmptyScoreEvidence(result);
        }
    }

    private void assertEmptyScoreEvidence(AnalysisResult result) {
        assertTrue(result.getScores().isEmpty());
        assertTrue(result.getRawScores().isEmpty());
        assertTrue(result.getEffectiveScores().isEmpty());
        assertTrue(result.getProductSuitabilityScores().isEmpty());
        assertTrue(result.getScoreDetails().isEmpty());
        assertTrue(result.getScoreSemanticsWarnings().isEmpty());
        assertEquals(AnalysisScoreSemantics.CURRENT_VERSION, result.getScoreSemanticsVersion());
        assertEquals(AnalysisScoreSemanticsFingerprint.sha256(Map.of()),
                result.getScoreSemanticsFingerprintSha256());
        assertSame(result.getScoreDetails(), result.getScoreDetails());
    }

    private AnalysisResult productResult() {
        TaxonomyNodeDto root = node("IP", null, "CATEGORY");
        TaxonomyNodeDto family = node("IP-F", "IP", "PRODUCT_FAMILY");
        family.setChildren(List.of(node("IP-P", "IP-F", "PRODUCT")));
        root.setChildren(List.of(family));
        return new AnalysisResult(Map.of("IP", 100, "IP-F", 40, "IP-P", 80), List.of(root));
    }

    private TaxonomyNodeDto node(String code, String parentCode, String role) {
        TaxonomyNodeDto node = new TaxonomyNodeDto();
        node.setCode(code);
        node.setParentCode(parentCode);
        node.setAnalysisRole(role);
        return node;
    }
}
