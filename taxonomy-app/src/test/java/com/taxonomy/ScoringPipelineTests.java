package com.taxonomy;

import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the scoring pipeline fixes:
 * <ul>
 *   <li>Conditional normalization (only normalize when sum ≠ parentScore)</li>
 *   <li>{@link TaxonomyDiscrepancy} record creation and field access</li>
 * </ul>
 */
@SpringBootTest
class ScoringPipelineTests {

    @Autowired
    private LlmService llmService;

    // ── TaxonomyDiscrepancy DTO ───────────────────────────────────────────────

    @Test
    void taxonomyDiscrepancyRecordFields() {
        TaxonomyDiscrepancy d = new TaxonomyDiscrepancy("CP", 75, 120);
        assertThat(d.parentCode()).isEqualTo("CP");
        assertThat(d.expectedParentScore()).isEqualTo(75);
        assertThat(d.actualChildSum()).isEqualTo(120);
    }

    @Test
    void taxonomyDiscrepancyEquality() {
        TaxonomyDiscrepancy d1 = new TaxonomyDiscrepancy("CP", 75, 120);
        TaxonomyDiscrepancy d2 = new TaxonomyDiscrepancy("CP", 75, 120);
        assertThat(d1).isEqualTo(d2);
        assertThat(d1.hashCode()).isEqualTo(d2.hashCode());
    }

    @Test
    void taxonomyDiscrepancyToString() {
        TaxonomyDiscrepancy d = new TaxonomyDiscrepancy("CP", 75, 120);
        assertThat(d.toString()).contains("CP", "75", "120");
    }

    // ── normalizeToParent — pass-through when sum already matches ─────────────

    @Test
    void normalizeToParentPassesThroughWhenSumMatchesTarget() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 40);
        scores.put("B", 35);
        scores.put("C", 25);
        // Sum = 100, target = 100 → should pass through unchanged
        Map<String, Integer> result = llmService.normalizeToParent(scores, 100);
        assertThat(result.get("A")).isEqualTo(40);
        assertThat(result.get("B")).isEqualTo(35);
        assertThat(result.get("C")).isEqualTo(25);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(100);
    }

    @Test
    void normalizeToParentNormalizesWhenSumExceedsTarget() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 60);
        scores.put("B", 80);
        // Sum = 140, target = 75 → must normalize down
        Map<String, Integer> result = llmService.normalizeToParent(scores, 75);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(75);
    }

    @Test
    void normalizeToParentNormalizesWhenSumBelowTarget() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("A", 10);
        scores.put("B", 15);
        // Sum = 25, target = 75 → must normalize up
        Map<String, Integer> result = llmService.normalizeToParent(scores, 75);
        assertThat(result.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(75);
    }

    // ── AnalysisResult discrepancies field ────────────────────────────────────

    @Test
    void analysisResultDiscrepanciesDefaultsToEmpty() {
        var result = new com.taxonomy.dto.AnalysisResult();
        assertThat(result.getDiscrepancies()).isNotNull().isEmpty();
    }

    @Test
    void analysisResultDiscrepanciesCanBeSet() {
        var result = new com.taxonomy.dto.AnalysisResult();
        var discrepancy = new TaxonomyDiscrepancy("CP", 75, 120);
        result.setDiscrepancies(java.util.List.of(discrepancy));
        assertThat(result.getDiscrepancies()).hasSize(1);
        assertThat(result.getDiscrepancies().get(0).parentCode()).isEqualTo("CP");
    }
}
