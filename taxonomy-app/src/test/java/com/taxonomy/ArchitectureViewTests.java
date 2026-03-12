package com.taxonomy;

import com.taxonomy.dto.RequirementAnchor;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.repository.TaxonomyRelationRepository;
import com.taxonomy.service.PropagationResult;
import com.taxonomy.service.RelationTraversalService;
import com.taxonomy.service.RelevancePropagationService;
import com.taxonomy.service.RequirementArchitectureViewService;
import com.taxonomy.service.TaxonomyRelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ArchitectureViewTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequirementArchitectureViewService architectureViewService;

    @Autowired
    private RelevancePropagationService propagationService;

    @Autowired
    private RelationTraversalService traversalService;

    @Autowired
    private TaxonomyRelationService relationService;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    @BeforeEach
    void cleanRelations() {
        relationRepository.deleteAll();
    }

    // ── Anchor Selection Tests ──────────────────────────────────────────────

    @Test
    void anchorSelectionPicksAllAbove70() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 91);
        scores.put("CP", 80);
        scores.put("CR", 75);
        scores.put("CI", 40);

        RequirementArchitectureView view = architectureViewService.build(scores, "test", 0);

        assertThat(view.getAnchors()).hasSize(3);
        assertThat(view.getAnchors()).allMatch(a -> a.getDirectScore() >= 70);
    }

    @Test
    void anchorSelectionFallsBackToTop3WhenFewerThan3Above70() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 80);
        scores.put("CP", 55);
        scores.put("CR", 52);
        scores.put("CI", 30);

        RequirementArchitectureView view = architectureViewService.build(scores, "test", 0);

        // BP is above 70, but only 1 anchor → fallback to top-3 above 50
        assertThat(view.getAnchors()).hasSize(3);
        assertThat(view.getAnchors().stream().map(RequirementAnchor::getNodeCode).toList())
                .containsExactlyInAnyOrder("BP", "CP", "CR");
    }

    @Test
    void anchorSelectionReturnsEmptyWhenAllScoresBelowThreshold() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 30);
        scores.put("CP", 20);

        RequirementArchitectureView view = architectureViewService.build(scores, "test", 0);

        assertThat(view.getAnchors()).isEmpty();
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void buildReturnsEmptyViewForNullScores() {
        RequirementArchitectureView view = architectureViewService.build(null, "test", 0);

        assertThat(view.getAnchors()).isEmpty();
        assertThat(view.getIncludedElements()).isEmpty();
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void buildReturnsEmptyViewForEmptyScores() {
        RequirementArchitectureView view = architectureViewService.build(Map.of(), "test", 0);

        assertThat(view.getAnchors()).isEmpty();
        assertThat(view.getNotes()).isNotEmpty();
    }

    // ── Propagation Tests ───────────────────────────────────────────────────

    @Test
    void propagationWithNoRelationsReturnsOnlyAnchors() {
        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        assertThat(result.getRelevanceMap()).containsKey("BP");
        assertThat(result.getRelevanceMap().get("BP")).isEqualTo(0.91);
        assertThat(result.getHopDistanceMap().get("BP")).isEqualTo(0);
        assertThat(result.getReasonMap().get("BP")).isEqualTo("direct-match");
    }

    @Test
    void propagationTraversesSupportsRelation() {
        // Create a SUPPORTS relation: BP → CP
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        assertThat(result.getRelevanceMap()).containsKey("CP");
        // BP (0.91) * SUPPORTS (0.75) = 0.6825
        double expected = 0.91 * 0.75;
        assertThat(result.getRelevanceMap().get("CP")).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.001));
        assertThat(result.getHopDistanceMap().get("CP")).isEqualTo(1);
    }

    @Test
    void propagationTraversesRealizesRelation() {
        relationService.createRelation("BP", "CP", RelationType.REALIZES, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        assertThat(result.getRelevanceMap()).containsKey("CP");
        double expected = 0.91 * 0.80;
        assertThat(result.getRelevanceMap().get("CP")).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void propagationTraversesUsesRelation() {
        relationService.createRelation("BP", "CP", RelationType.USES, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        assertThat(result.getRelevanceMap()).containsKey("CP");
        double expected = 0.91 * 0.65;
        assertThat(result.getRelevanceMap().get("CP")).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void propagationDoesNotTraverseNonWhitelistedRelations() {
        relationService.createRelation("BP", "CP", RelationType.RELATED_TO, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        assertThat(result.getRelevanceMap()).doesNotContainKey("CP");
    }

    @Test
    void propagationRespectsMaxHops() {
        // Chain: BP → CP → CR (2 hops, both should appear)
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CP", "CR", RelationType.SUPPORTS, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        assertThat(result.getRelevanceMap()).containsKey("CP");
        assertThat(result.getRelevanceMap()).containsKey("CR");
        assertThat(result.getHopDistanceMap().get("CP")).isEqualTo(1);
        assertThat(result.getHopDistanceMap().get("CR")).isEqualTo(2);
    }

    @Test
    void propagationStopsAt2Hops() {
        // Chain: BP → CP → CR → CI (3 hops — CI should NOT appear)
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CP", "CR", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CR", "CI", RelationType.SUPPORTS, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        assertThat(result.getRelevanceMap()).containsKey("CP");
        assertThat(result.getRelevanceMap()).containsKey("CR");
        assertThat(result.getRelevanceMap()).doesNotContainKey("CI");
    }

    @Test
    void propagationAppliesHopDecay() {
        // BP → CP (hop 1) → CR (hop 2 with decay)
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CP", "CR", RelationType.SUPPORTS, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.91);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        // Hop 1: 0.91 * 0.75 = 0.6825
        // Hop 2: 0.6825 * 0.75 * 0.70 = 0.358...
        double hop1 = 0.91 * 0.75;
        double hop2 = hop1 * 0.75 * 0.70;
        assertThat(result.getRelevanceMap().get("CR")).isCloseTo(hop2, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void propagationDiscardsRelevanceBelowThreshold() {
        // Use a low score → propagated value should be below 0.35
        relationService.createRelation("BP", "CP", RelationType.USES, null, "test");

        Map<String, Double> anchorRelevances = Map.of("BP", 0.50);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        // 0.50 * 0.65 = 0.325 < 0.35 → should be discarded
        assertThat(result.getRelevanceMap()).doesNotContainKey("CP");
    }

    @Test
    void propagationMergesMultiplePathsKeepingHighest() {
        // Two paths to CP: via SUPPORTS and via REALIZES
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("CR", "CP", RelationType.REALIZES, null, "test");

        Map<String, Double> anchorRelevances = new LinkedHashMap<>();
        anchorRelevances.put("BP", 0.91);
        anchorRelevances.put("CR", 0.85);
        PropagationResult result = propagationService.propagate(anchorRelevances);

        // Via SUPPORTS: 0.91 * 0.75 = 0.6825
        // Via REALIZES: 0.85 * 0.80 = 0.68
        // Should keep highest = 0.6825
        double expected = 0.91 * 0.75;
        assertThat(result.getRelevanceMap().get("CP")).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.001));
    }

    // ── Full Architecture View Build Tests ──────────────────────────────────

    @Test
    void buildIncludesAnchorAsElement() {
        Map<String, Integer> scores = Map.of("BP", 91);

        RequirementArchitectureView view = architectureViewService.build(scores, "test", 0);

        assertThat(view.getIncludedElements()).isNotEmpty();
        assertThat(view.getIncludedElements().get(0).getNodeCode()).isEqualTo("BP");
        assertThat(view.getIncludedElements().get(0).isAnchor()).isTrue();
        assertThat(view.getIncludedElements().get(0).getIncludedBecause()).isEqualTo("direct-match");
        assertThat(view.getIncludedElements().get(0).getHopDistance()).isEqualTo(0);
    }

    @Test
    void buildIncludesPropagatedElementsAndRelationships() {
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");

        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 91);

        RequirementArchitectureView view = architectureViewService.build(scores, "test", 0);

        assertThat(view.getIncludedElements()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(view.getIncludedElements().stream()
                .map(e -> e.getNodeCode()).toList())
                .contains("BP", "CP");
        assertThat(view.getIncludedRelationships()).isNotEmpty();
        assertThat(view.getIncludedRelationships().get(0).getRelationType()).isEqualTo("SUPPORTS");
    }

    @Test
    void buildAddsNoteWhenNoRelationsExist() {
        Map<String, Integer> scores = Map.of("BP", 91);

        RequirementArchitectureView view = architectureViewService.build(scores, "test", 0);

        assertThat(view.getNotes()).anyMatch(n -> n.contains("No traversable relations"));
    }

    // ── API Integration Tests ───────────────────────────────────────────────

    @Test
    void analyzeWithoutFlagDoesNotIncludeArchitectureView() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice communications\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.tree").isArray())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.architectureView").doesNotExist());
    }

    @Test
    void analyzeWithFlagFalseDoesNotIncludeArchitectureView() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice communications\",\"includeArchitectureView\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.architectureView").doesNotExist());
    }

    @Test
    void analyzeWithFlagTrueIncludesArchitectureView() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice communications\",\"includeArchitectureView\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.tree").isArray())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.architectureView").exists())
                .andExpect(jsonPath("$.architectureView.anchors").isArray())
                .andExpect(jsonPath("$.architectureView.includedElements").isArray())
                .andExpect(jsonPath("$.architectureView.includedRelationships").isArray())
                .andExpect(jsonPath("$.architectureView.notes").isArray());
    }

    @Test
    void analyzeWithFlagTrueStillReturnsExistingFields() throws Exception {
        // Regression test: existing fields must be preserved
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Manage satellite communications for deployed forces\",\"includeArchitectureView\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.tree").isArray())
                .andExpect(jsonPath("$.tree.length()").value(8))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.warnings").isArray());
    }
}
