package com.taxonomy.architecture.pipeline;

import com.taxonomy.dto.RequirementAnchor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AnchorSelectionStep}.
 *
 * <p>No Spring context required — the step is a pure function.
 */
class AnchorSelectionStepTest {

    private final AnchorSelectionStep step = new AnchorSelectionStep();

    // ── High-threshold selection ─────────────────────────────────────────────

    @Test
    void selectsAllNodesAbove70AsHighAnchors() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 91);
        scores.put("CP", 80);
        scores.put("CR", 75);
        scores.put("CI", 40);

        List<RequirementAnchor> anchors = step.select(scores);

        assertThat(anchors).hasSize(3);
        assertThat(anchors).allMatch(a -> a.getDirectScore() >= 70);
    }

    @Test
    void highAnchorsAreSortedByScoreDescending() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 75);
        scores.put("CP", 91);
        scores.put("CR", 80);

        List<RequirementAnchor> anchors = step.select(scores);

        assertThat(anchors).extracting(RequirementAnchor::getDirectScore)
                .containsExactly(91, 80, 75);
    }

    @Test
    void highAnchorReasonIsHighDirectMatch() {
        Map<String, Integer> scores = Map.of("BP", 91, "CP", 80, "CR", 75);
        List<RequirementAnchor> anchors = step.select(scores);
        assertThat(anchors).allMatch(a -> "high direct match".equals(a.getReason()));
    }

    // ── Fallback to top-3 above low threshold ────────────────────────────────

    @Test
    void fallsBackToTop3WhenFewerThan3AboveHighThreshold() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 80);  // above 70
        scores.put("CP", 55);  // above 50 (fallback candidate)
        scores.put("CR", 52);  // above 50 (fallback candidate)
        scores.put("CI", 30);  // below 50 (excluded)

        List<RequirementAnchor> anchors = step.select(scores);

        assertThat(anchors).hasSize(3);
        assertThat(anchors.stream().map(RequirementAnchor::getNodeCode).toList())
                .containsExactlyInAnyOrder("BP", "CP", "CR");
    }

    @Test
    void fallbackAnchorsAreOrderedByScoreDescending() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 52);
        scores.put("CP", 65);
        scores.put("CR", 58);

        List<RequirementAnchor> anchors = step.select(scores);

        // All below 70, so fallback: top-3 sorted by score descending
        assertThat(anchors).extracting(RequirementAnchor::getDirectScore)
                .containsExactly(65, 58, 52);
    }

    @Test
    void fallbackReasonIsTopCandidateFallback() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 52);
        scores.put("CP", 65);

        List<RequirementAnchor> anchors = step.select(scores);

        assertThat(anchors).allMatch(a -> "top candidate (fallback)".equals(a.getReason()));
    }

    // ── Empty / no qualifying nodes ──────────────────────────────────────────

    @Test
    void returnsEmptyWhenAllScoresBelowLowThreshold() {
        Map<String, Integer> scores = Map.of("BP", 30, "CP", 20);
        List<RequirementAnchor> anchors = step.select(scores);
        assertThat(anchors).isEmpty();
    }

    @Test
    void returnsEmptyForEmptyScores() {
        List<RequirementAnchor> anchors = step.select(Map.of());
        assertThat(anchors).isEmpty();
    }

    @Test
    void limitsFallbackToAtMost3Candidates() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        // 5 nodes, all between 50 and 70 → fallback mode, should return only 3
        scores.put("BP", 68);
        scores.put("CP", 65);
        scores.put("CR", 62);
        scores.put("CI", 55);
        scores.put("CO", 51);

        List<RequirementAnchor> anchors = step.select(scores);

        assertThat(anchors).hasSize(3);
    }
}
