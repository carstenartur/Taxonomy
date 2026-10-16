package com.taxonomy;

import com.taxonomy.analysis.service.LlmService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LlmService#normalizeToParent(Map, double)} and the delegating
 * {@link LlmService#normalizeToHundred(Map)}.
 */
class LlmServiceNormalizationTests {

    private final LlmService llmService = new LlmService(
            null, null, new ObjectMapper(), null, null, null, null);

    // ── normalizeToHundred (delegates to normalizeToParent with target=100) ─────

    @Test
    void normalizeToHundredSumsToOneHundred() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 70d);
        scores.put("B", 30d);
        Map<String, Double> result = llmService.normalizeToHundred(scores);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredPreservesProportions() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 40d);
        scores.put("B", 60d);
        Map<String, Double> result = llmService.normalizeToHundred(scores);
        assertThat(result.get("A")).isEqualTo(40);
        assertThat(result.get("B")).isEqualTo(60);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredHandlesAllZeros() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 0d);
        scores.put("B", 0d);
        scores.put("C", 0d);
        Map<String, Double> result = llmService.normalizeToHundred(scores);
        // All zeros should remain unchanged (no normalization)
        assertThat(result.get("A")).isEqualTo(0);
        assertThat(result.get("B")).isEqualTo(0);
        assertThat(result.get("C")).isEqualTo(0);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(0);
    }

    @Test
    void normalizeToHundredHandlesSingleNonZeroNode() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 50d);
        scores.put("B", 0d);
        scores.put("C", 0d);
        Map<String, Double> result = llmService.normalizeToHundred(scores);
        assertThat(result.get("A")).isEqualTo(100);
        assertThat(result.get("B")).isEqualTo(0);
        assertThat(result.get("C")).isEqualTo(0);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredRoundingIsExact() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 1d);
        scores.put("B", 1d);
        scores.put("C", 1d);
        Map<String, Double> result = llmService.normalizeToHundred(scores);
        // Three equal values must still sum to exactly 100
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredWithArbitraryValues() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 10d);
        scores.put("B", 20d);
        scores.put("C", 30d);
        scores.put("D", 40d);
        Map<String, Double> result = llmService.normalizeToHundred(scores);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100);
        // Proportions: A=10%, B=20%, C=30%, D=40% (exactly)
        assertThat(result.get("A")).isEqualTo(10);
        assertThat(result.get("B")).isEqualTo(20);
        assertThat(result.get("C")).isEqualTo(30);
        assertThat(result.get("D")).isEqualTo(40);
    }

    // ── normalizeToParent — distribution model ────────────────────────────────

    @Test
    void normalizeToParentSumsToTarget() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 60d);
        scores.put("B", 40d);
        Map<String, Double> result = llmService.normalizeToParent(scores, 70);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(70);
    }

    @Test
    void normalizeToParentPreservesProportions() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 40d);
        scores.put("B", 60d);
        // A:B = 40:60, target = 50 → A=20, B=30
        Map<String, Double> result = llmService.normalizeToParent(scores, 50);
        assertThat(result.get("A")).isEqualTo(20);
        assertThat(result.get("B")).isEqualTo(30);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(50);
    }

    @Test
    void normalizeToParentHandlesAllZeros() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 0d);
        scores.put("B", 0d);
        Map<String, Double> result = llmService.normalizeToParent(scores, 40);
        // All zeros — no normalization possible, returned unchanged
        assertThat(result.get("A")).isEqualTo(0);
        assertThat(result.get("B")).isEqualTo(0);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(0);
    }

    @Test
    void normalizeToParentChildrenNeverExceedParent() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 80d);
        scores.put("B", 20d);
        double parentScore = 40;
        Map<String, Double> result = llmService.normalizeToParent(scores, parentScore);
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(parentScore);
        result.values().forEach(v -> assertThat(v).isLessThanOrEqualTo(parentScore));
    }

    @Test
    void normalizeToParentRoundingIsExact() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 1d);
        scores.put("B", 1d);
        scores.put("C", 1d);
        Map<String, Double> result = llmService.normalizeToParent(scores, 60);
        // Three equal values must sum to exactly 60
        assertThat(result.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(60);
    }

    @Test
    void normalizeToParentWithTargetHundredEqualsNormalizeToHundred() {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("A", 30d);
        scores.put("B", 70d);
        Map<String, Double> viaParent = llmService.normalizeToParent(scores, 100);
        Map<String, Double> viaHundred = llmService.normalizeToHundred(scores);
        assertThat(viaParent).isEqualTo(viaHundred);
    }
}
