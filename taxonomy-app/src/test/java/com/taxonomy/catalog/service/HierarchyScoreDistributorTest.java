package com.taxonomy.catalog.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HierarchyScoreDistributor}.
 */
@ExtendWith(MockitoExtension.class)
class HierarchyScoreDistributorTest {

    @Mock
    private TaxonomyService taxonomyService;

    @InjectMocks
    private HierarchyScoreDistributor distributor;

    // ── distribute() ───────────────────────────────────────────────────────

    @Test
    void distributeAssignsScoresToAllNodes() {
        // Build a small taxonomy:  ROOT → A, B   where A → A1, A2
        TaxonomyNode root = node("RT", 0, null);
        TaxonomyNode a    = node("RT-1000", 1, "RT");
        TaxonomyNode b    = node("RT-1001", 1, "RT");
        TaxonomyNode a1   = node("RT-1010", 2, "RT");
        TaxonomyNode a2   = node("RT-1020", 2, "RT");

        when(taxonomyService.getRootNodes()).thenReturn(List.of(root));
        when(taxonomyService.getChildrenOf("RT")).thenReturn(List.of(a, b));
        when(taxonomyService.getChildrenOf("RT-1000")).thenReturn(List.of(a1, a2));
        when(taxonomyService.getChildrenOf("RT-1001")).thenReturn(List.of());
        when(taxonomyService.getChildrenOf("RT-1010")).thenReturn(List.of());
        when(taxonomyService.getChildrenOf("RT-1020")).thenReturn(List.of());

        var result = distributor.distribute(
                Map.of("RT", 100),
                Map.of("RT", "Test root"));

        Map<String, Integer> scores = result.scores();
        assertThat(scores).containsKey("RT");
        assertThat(scores.get("RT")).isEqualTo(100);

        // Children of ROOT must sum to 100
        int aScore = scores.get("RT-1000");
        int bScore = scores.get("RT-1001");
        assertThat(aScore + bScore).isEqualTo(100);

        // Grandchildren of A must sum to A's score
        int a1Score = scores.get("RT-1010");
        int a2Score = scores.get("RT-1020");
        assertThat(a1Score + a2Score).isEqualTo(aScore);
    }

    @Test
    void distributeZeroScoreAssignsZeroToAllChildren() {
        TaxonomyNode root = node("RT", 0, null);
        TaxonomyNode a    = node("RT-1000", 1, "RT");

        when(taxonomyService.getRootNodes()).thenReturn(List.of(root));
        when(taxonomyService.getChildrenOf("RT")).thenReturn(List.of(a));
        when(taxonomyService.getChildrenOf("RT-1000")).thenReturn(List.of());

        var result = distributor.distribute(
                Map.of("RT", 0),
                Map.of("RT", "Not relevant"));

        assertThat(result.scores().get("RT")).isZero();
        assertThat(result.scores().get("RT-1000")).isZero();
    }

    @Test
    void distributeReturnsReasonsForAllNodes() {
        TaxonomyNode root = node("CP", 0, "CP");
        TaxonomyNode child = node("CP-1000", 1, "CP");

        when(taxonomyService.getRootNodes()).thenReturn(List.of(root));
        when(taxonomyService.getChildrenOf("CP")).thenReturn(List.of(child));
        when(taxonomyService.getChildrenOf("CP-1000")).thenReturn(List.of());

        var result = distributor.distribute(
                Map.of("CP", 80),
                Map.of("CP", "Capabilities are relevant"));

        assertThat(result.reasons()).containsEntry("CP", "Capabilities are relevant");
        assertThat(result.reasons()).containsEntry("CP-1000", "Capabilities are relevant");
    }

    // ── fillIntermediateScores() ───────────────────────────────────────────

    @Test
    void fillIntermediateScoresInterpolatesMissingNodes() {
        // Path: RT → RT-1000 → RT-1010 → RT-1020
        // Scores given: RT=100, RT-1020=40.  Missing: RT-1000, RT-1010
        TaxonomyNode root   = node("RT", 0, null);
        TaxonomyNode middle = node("RT-1000", 1, "RT");
        TaxonomyNode deep   = node("RT-1010", 2, "RT");
        TaxonomyNode leaf   = node("RT-1020", 3, "RT");

        when(taxonomyService.getPathToRoot("RT-1020"))
                .thenReturn(List.of(root, middle, deep, leaf));

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("RT", 100);
        scores.put("RT-1020", 40);

        distributor.fillIntermediateScores(scores);

        // RT-1000 should be interpolated: 100 + (40-100)*1/3 = 80
        assertThat(scores).containsKey("RT-1000");
        assertThat(scores.get("RT-1000")).isEqualTo(80);

        // RT-1010 should be interpolated: 100 + (40-100)*2/3 = 60
        assertThat(scores).containsKey("RT-1010");
        assertThat(scores.get("RT-1010")).isEqualTo(60);
    }

    @Test
    void fillIntermediateScoresSkipsDirectChildOfRoot() {
        // Path: RT → RT-1000 — only 2 levels, nothing to fill
        TaxonomyNode root  = node("RT", 0, null);
        TaxonomyNode child = node("RT-1000", 1, "RT");

        when(taxonomyService.getPathToRoot("RT-1000"))
                .thenReturn(List.of(root, child));

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("RT", 100);
        scores.put("RT-1000", 80);

        distributor.fillIntermediateScores(scores);

        // No changes — both nodes already present
        assertThat(scores).hasSize(2);
    }

    @Test
    void fillIntermediateScoresHandlesAlreadyScoredIntermediates() {
        // Path: RT → RT-1000 → RT-1010.  All three already scored.
        TaxonomyNode root   = node("RT", 0, null);
        TaxonomyNode middle = node("RT-1000", 1, "RT");
        TaxonomyNode leaf   = node("RT-1010", 2, "RT");

        when(taxonomyService.getPathToRoot("RT-1010"))
                .thenReturn(List.of(root, middle, leaf));
        when(taxonomyService.getPathToRoot("RT-1000"))
                .thenReturn(List.of(root, middle));

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("RT", 100);
        scores.put("RT-1000", 90);
        scores.put("RT-1010", 70);

        distributor.fillIntermediateScores(scores);

        // Nothing changed
        assertThat(scores).containsEntry("RT", 100);
        assertThat(scores).containsEntry("RT-1000", 90);
        assertThat(scores).containsEntry("RT-1010", 70);
    }

    @Test
    void fillIntermediateScoresHandlesDeepHierarchy() {
        // Path: BR → BR-1000 → BR-1100 → BR-1110 → BR-1111 → BR-1043
        // Only BR and BR-1043 are scored.
        TaxonomyNode n0 = node("BR", 0, "BR");
        TaxonomyNode n1 = node("BR-1000", 1, "BR");
        TaxonomyNode n2 = node("BR-1100", 2, "BR");
        TaxonomyNode n3 = node("BR-1110", 3, "BR");
        TaxonomyNode n4 = node("BR-1111", 4, "BR");
        TaxonomyNode n5 = node("BR-1043", 5, "BR");

        when(taxonomyService.getPathToRoot("BR-1043"))
                .thenReturn(List.of(n0, n1, n2, n3, n4, n5));

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BR", 55);
        scores.put("BR-1043", 35);

        distributor.fillIntermediateScores(scores);

        // 4 intermediates should be filled: BR-1000, BR-1100, BR-1110, BR-1111
        assertThat(scores).hasSize(6);
        assertThat(scores).containsKeys("BR-1000", "BR-1100", "BR-1110", "BR-1111");

        // Scores should decrease linearly: 55 → 35 over 5 steps
        // BR-1000: 55 + (35-55)*1/5 = 55 - 4 = 51
        assertThat(scores.get("BR-1000")).isEqualTo(51);
        // BR-1100: 55 + (35-55)*2/5 = 55 - 8 = 47
        assertThat(scores.get("BR-1100")).isEqualTo(47);
        // BR-1110: 55 + (35-55)*3/5 = 55 - 12 = 43
        assertThat(scores.get("BR-1110")).isEqualTo(43);
        // BR-1111: 55 + (35-55)*4/5 = 55 - 16 = 39
        assertThat(scores.get("BR-1111")).isEqualTo(39);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static TaxonomyNode node(String code, int level, String root) {
        TaxonomyNode n = new TaxonomyNode();
        n.setCode(code);
        n.setLevel(level);
        n.setTaxonomyRoot(root != null ? root : code);
        n.setNameEn(code);
        return n;
    }
}
