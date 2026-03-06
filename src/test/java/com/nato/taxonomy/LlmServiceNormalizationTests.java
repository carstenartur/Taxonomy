package com.nato.taxonomy;

import com.nato.taxonomy.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LlmService#normalizeToHundred(Map)}.
 */
@SpringBootTest
class LlmServiceNormalizationTests {

    @Autowired
    private LlmService llmService;

    @Test
    void normalizeToHundredSumsToOneHundred() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 70);
        scores.put("B", 30);
        Map<String, Integer> result = llmService.normalizeToHundred(scores);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredPreservesProportions() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 40);
        scores.put("B", 60);
        Map<String, Integer> result = llmService.normalizeToHundred(scores);
        assertThat(result.get("A")).isEqualTo(40);
        assertThat(result.get("B")).isEqualTo(60);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredHandlesAllZeros() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 0);
        scores.put("B", 0);
        scores.put("C", 0);
        Map<String, Integer> result = llmService.normalizeToHundred(scores);
        // All zeros should remain unchanged (no normalization)
        assertThat(result.get("A")).isEqualTo(0);
        assertThat(result.get("B")).isEqualTo(0);
        assertThat(result.get("C")).isEqualTo(0);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(0);
    }

    @Test
    void normalizeToHundredHandlesSingleNonZeroNode() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 50);
        scores.put("B", 0);
        scores.put("C", 0);
        Map<String, Integer> result = llmService.normalizeToHundred(scores);
        assertThat(result.get("A")).isEqualTo(100);
        assertThat(result.get("B")).isEqualTo(0);
        assertThat(result.get("C")).isEqualTo(0);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredRoundingIsExact() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 1);
        scores.put("B", 1);
        scores.put("C", 1);
        Map<String, Integer> result = llmService.normalizeToHundred(scores);
        // Three equal values must still sum to exactly 100
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToHundredWithArbitraryValues() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 10);
        scores.put("B", 20);
        scores.put("C", 30);
        scores.put("D", 40);
        Map<String, Integer> result = llmService.normalizeToHundred(scores);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
        // Proportions: A=10%, B=20%, C=30%, D=40% (exactly)
        assertThat(result.get("A")).isEqualTo(10);
        assertThat(result.get("B")).isEqualTo(20);
        assertThat(result.get("C")).isEqualTo(30);
        assertThat(result.get("D")).isEqualTo(40);
    }
}
