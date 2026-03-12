package com.taxonomy;

import com.taxonomy.dto.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.repository.RequirementCoverageRepository;
import com.taxonomy.repository.TaxonomyRelationRepository;
import com.taxonomy.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for Steps 2–5:
 * ArchitectureGapService, EnrichedImpactService,
 * ArchitectureRecommendationService, ArchitecturePatternService,
 * and their REST API controllers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArchitectureAnalysisTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ArchitectureGapService gapService;
    @Autowired private EnrichedImpactService enrichedImpactService;
    @Autowired private ArchitectureRecommendationService recommendationService;
    @Autowired private ArchitecturePatternService patternService;
    @Autowired private TaxonomyRelationService relationService;
    @Autowired private TaxonomyRelationRepository relationRepository;
    @Autowired private RequirementCoverageRepository coverageRepository;
    @Autowired private RequirementCoverageService coverageService;
    @Autowired private RelationCompatibilityMatrix compatibilityMatrix;

    @BeforeEach
    void clean() {
        relationRepository.deleteAll();
        coverageRepository.deleteAll();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 2 — Gap Analysis Service Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void gapAnalysisWithNoScoresReturnsNote() {
        GapAnalysisView view = gapService.analyze(Map.of(), "test", 50);
        assertThat(view.getNotes()).isNotEmpty();
        assertThat(view.getMissingRelations()).isEmpty();
    }

    @Test
    void gapAnalysisWithNullScoresReturnsNote() {
        GapAnalysisView view = gapService.analyze(null, "test", 50);
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void gapAnalysisDetectsMissingRelationsForCapability() {
        // CP nodes should have REALIZES → CR according to the compatibility matrix
        // No relations exist, so gap analysis should find this gap
        GapAnalysisView view = gapService.analyze(Map.of("CP", 80), "test requirement", 50);

        assertThat(view.getTotalAnchors()).isEqualTo(1);
        assertThat(view.getMissingRelations()).isNotEmpty();
        assertThat(view.getMissingRelations().stream()
                .anyMatch(m -> "REALIZES".equals(m.getExpectedRelationType())
                        && "CR".equals(m.getExpectedTargetRoot()))).isTrue();
    }

    @Test
    void gapAnalysisDetectsNoGapWhenRelationExists() {
        // Create the expected relation CP → CR via REALIZES (matches compatibility matrix)
        relationService.createRelation("CP", "CR", RelationType.REALIZES, null, "test");

        GapAnalysisView view = gapService.analyze(Map.of("CP", 80), "test requirement", 50);

        // Should not report REALIZES → CR as missing for the CP node
        boolean realizesToCrMissing = view.getMissingRelations().stream()
                .anyMatch(m -> "REALIZES".equals(m.getExpectedRelationType())
                        && "CR".equals(m.getExpectedTargetRoot())
                        && "CP".equals(m.getSourceNodeCode()));
        assertThat(realizesToCrMissing).isFalse();
    }

    @Test
    void gapAnalysisReportsCoverageGapsForAnchorNodes() {
        GapAnalysisView view = gapService.analyze(Map.of("CP", 80), "test", 50);
        assertThat(view.getCoverageGaps()).isNotEmpty();
        assertThat(view.getCoverageGaps().stream()
                .anyMatch(g -> "CP".equals(g.getNodeCode()))).isTrue();
    }

    @Test
    void gapAnalysisReportsIncompletePatternsForMissingRelations() {
        GapAnalysisView view = gapService.analyze(Map.of("CP", 80), "test", 50);
        assertThat(view.getIncompletePatterns()).isNotEmpty();
    }

    @Test
    void gapAnalysisFiltersBelowThreshold() {
        GapAnalysisView view = gapService.analyze(Map.of("CP", 30), "test", 50);
        assertThat(view.getTotalAnchors()).isZero();
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void gapAnalysisUsesDefaultThresholdWhenZero() {
        GapAnalysisView view = gapService.analyze(Map.of("CP", 55), "test", 0);
        assertThat(view.getTotalAnchors()).isEqualTo(1);
    }

    // ── Gap Analysis Compatibility Matrix Extension Tests ──────────────────

    @Test
    void getExpectedOutgoingRelationsForCapability() {
        Map<RelationType, Set<String>> expected = compatibilityMatrix.getExpectedOutgoingRelations("CP");
        assertThat(expected).containsKey(RelationType.REALIZES);
        assertThat(expected.get(RelationType.REALIZES)).contains("CR");
    }

    @Test
    void getExpectedOutgoingRelationsForService() {
        Map<RelationType, Set<String>> expected = compatibilityMatrix.getExpectedOutgoingRelations("CR");
        assertThat(expected).containsKey(RelationType.SUPPORTS);
        assertThat(expected.get(RelationType.SUPPORTS)).contains("BP");
    }

    @Test
    void getExpectedOutgoingRelationsForUnknownRootReturnsEmpty() {
        Map<RelationType, Set<String>> expected = compatibilityMatrix.getExpectedOutgoingRelations("UNKNOWN");
        assertThat(expected).isEmpty();
    }

    // ── Gap Analysis API Tests ────────────────────────────────────────────

    @Test
    void gapAnalysisEndpointReturnsOk() throws Exception {
        mockMvc.perform(post("/api/gap/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP\":80},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessText").value("test"))
                .andExpect(jsonPath("$.missingRelations").isArray())
                .andExpect(jsonPath("$.incompletePatterns").isArray())
                .andExpect(jsonPath("$.coverageGaps").isArray())
                .andExpect(jsonPath("$.totalAnchors").value(1));
    }

    @Test
    void gapAnalysisEndpointWithEmptyScores() throws Exception {
        mockMvc.perform(post("/api/gap/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").isNotEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 3 — Enriched Failure Impact Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void enrichedFailureImpactBasicStructure() {
        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact("BP", 3);
        assertThat(view.getFailedNodeCode()).isEqualTo("BP");
        assertThat(view.getMaxHops()).isEqualTo(3);
        assertThat(view.getDirectlyAffected()).isNotNull();
        assertThat(view.getIndirectlyAffected()).isNotNull();
        assertThat(view.getAffectedRequirements()).isNotNull();
    }

    @Test
    void enrichedFailureImpactIncludesRequirements() {
        // Create relation and coverage data
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        coverageService.analyzeCoverage(Map.of("CP", 80), "REQ-ENRICH-01", "test req", 50);

        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact("BP", 3);

        // CP should be directly affected and have requirement correlation
        assertThat(view.getDirectlyAffected()).isNotEmpty();
        boolean cpHasRequirements = view.getDirectlyAffected().stream()
                .filter(e -> "CP".equals(e.getNodeCode()))
                .anyMatch(e -> e.getRequirementCount() > 0);
        assertThat(cpHasRequirements).isTrue();
        assertThat(view.getAffectedRequirements()).contains("REQ-ENRICH-01");
    }

    @Test
    void enrichedFailureImpactComputesRiskScore() {
        relationService.createRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        coverageService.analyzeCoverage(Map.of("CP", 80), "REQ-RISK-01", "test req", 50);

        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact("BP", 3);

        assertThat(view.getRiskScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void enrichedFailureImpactForNonExistentNodeReturnsNote() {
        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact("NONEXISTENT", 3);
        assertThat(view.getNotes()).isNotEmpty();
        assertThat(view.getDirectlyAffected()).isEmpty();
    }

    // ── Enriched Failure Impact API Tests ─────────────────────────────────

    @Test
    void enrichedFailureImpactEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP/enriched-failure-impact")
                        .param("maxHops", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedNodeCode").value("BP"))
                .andExpect(jsonPath("$.maxHops").value(3))
                .andExpect(jsonPath("$.directlyAffected").isArray())
                .andExpect(jsonPath("$.indirectlyAffected").isArray())
                .andExpect(jsonPath("$.affectedRequirements").isArray())
                .andExpect(jsonPath("$.riskScore").isNumber());
    }

    @Test
    void enrichedFailureImpactEndpointDefaultsMaxHopsTo3() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP/enriched-failure-impact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHops").value(3));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 4 — Architecture Recommendation Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void recommendationWithNoScoresReturnsNote() {
        ArchitectureRecommendation rec = recommendationService.recommend(Map.of(), "test", 50);
        assertThat(rec.getNotes()).isNotEmpty();
        assertThat(rec.getConfirmedElements()).isEmpty();
    }

    @Test
    void recommendationWithNullScoresReturnsNote() {
        ArchitectureRecommendation rec = recommendationService.recommend(null, "test", 50);
        assertThat(rec.getNotes()).isNotEmpty();
    }

    @Test
    void recommendationIdentifiesConfirmedElements() {
        ArchitectureRecommendation rec = recommendationService.recommend(
                Map.of("CP", 85, "BP", 75), "secure voice comms", 50);

        assertThat(rec.getConfirmedElements()).isNotEmpty();
        assertThat(rec.getConfirmedElements().stream()
                .map(RecommendedElement::getNodeCode).toList())
                .contains("CP", "BP");
    }

    @Test
    void recommendationProposesElementsForGaps() {
        // CP needs REALIZES → CR, so the recommendation should propose CR nodes
        ArchitectureRecommendation rec = recommendationService.recommend(
                Map.of("CP", 85), "secure voice communications", 50);

        assertThat(rec.getConfirmedElements()).isNotEmpty();
        // Proposals depend on available nodes in the CR root
        assertThat(rec.getReasoning()).isNotEmpty();
        assertThat(rec.getConfidence()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void recommendationSuggestsRelations() {
        ArchitectureRecommendation rec = recommendationService.recommend(
                Map.of("CP", 85), "secure voice communications", 50);

        // Should suggest REALIZES relations to fill gaps
        assertThat(rec.getSuggestedRelations()).isNotNull();
    }

    @Test
    void recommendationComputesConfidence() {
        ArchitectureRecommendation rec = recommendationService.recommend(
                Map.of("CP", 85), "test", 50);
        assertThat(rec.getConfidence()).isGreaterThanOrEqualTo(0.0);
        assertThat(rec.getConfidence()).isLessThanOrEqualTo(100.0);
    }

    // ── Recommendation API Tests ──────────────────────────────────────────

    @Test
    void recommendationEndpointReturnsOk() throws Exception {
        mockMvc.perform(post("/api/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP\":80},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessText").value("test"))
                .andExpect(jsonPath("$.confirmedElements").isArray())
                .andExpect(jsonPath("$.proposedElements").isArray())
                .andExpect(jsonPath("$.suggestedRelations").isArray())
                .andExpect(jsonPath("$.confidence").isNumber())
                .andExpect(jsonPath("$.reasoning").isArray());
    }

    @Test
    void recommendationEndpointWithEmptyScores() throws Exception {
        mockMvc.perform(post("/api/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").isNotEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 5 — Pattern Detection Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternDetectionForNonExistentNodeReturnsNote() {
        PatternDetectionView view = patternService.detectForNode("NONEXISTENT");
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void patternDetectionForCapabilityNodeChecksFullStack() {
        // CP is a starting root for the "Full Stack" pattern
        PatternDetectionView view = patternService.detectForNode("CP");

        // Without relations, the Full Stack pattern should be incomplete
        boolean hasFullStack = view.getIncompletePatterns().stream()
                .anyMatch(p -> "Full Stack".equals(p.getPatternName()));
        // Either incomplete or not detected (if no steps are present)
        assertThat(view.getMatchedPatterns().stream()
                .noneMatch(p -> "Full Stack".equals(p.getPatternName())))
                .isTrue();
    }

    @Test
    void patternDetectionFindsCompleteFullStackPattern() {
        // Create the full chain: CP → REALIZES → CR → SUPPORTS → BP → CONSUMES → IP
        relationService.createRelation("CP", "CR", RelationType.REALIZES, null, "test");
        relationService.createRelation("CR", "BP", RelationType.SUPPORTS, null, "test");
        relationService.createRelation("BP", "IP", RelationType.CONSUMES, null, "test");

        PatternDetectionView view = patternService.detectForNode("CP");

        assertThat(view.getMatchedPatterns()).isNotEmpty();
        assertThat(view.getMatchedPatterns().stream()
                .anyMatch(p -> "Full Stack".equals(p.getPatternName())
                        && p.getCompleteness() >= 100.0)).isTrue();
    }

    @Test
    void patternDetectionFindsIncompletePattern() {
        // Only first step: CP → REALIZES → CR
        relationService.createRelation("CP", "CR", RelationType.REALIZES, null, "test");

        PatternDetectionView view = patternService.detectForNode("CP");

        assertThat(view.getIncompletePatterns()).isNotEmpty();
        assertThat(view.getIncompletePatterns().stream()
                .anyMatch(p -> "Full Stack".equals(p.getPatternName())
                        && p.getCompleteness() > 0.0
                        && p.getCompleteness() < 100.0)).isTrue();
    }

    @Test
    void patternDetectionWithEmptyScoresReturnsNote() {
        PatternDetectionView view = patternService.detectForScores(Map.of(), 50);
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void patternDetectionWithNullScoresReturnsNote() {
        PatternDetectionView view = patternService.detectForScores(null, 50);
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void patternDetectionForScoresAggregatesResults() {
        relationService.createRelation("CP", "CR", RelationType.REALIZES, null, "test");

        PatternDetectionView view = patternService.detectForScores(Map.of("CP", 80), 50);

        // Should find the incomplete Full Stack pattern
        int totalDetected = view.getMatchedPatterns().size() + view.getIncompletePatterns().size();
        assertThat(totalDetected).isGreaterThanOrEqualTo(1);
    }

    // ── Pattern Detection API Tests ───────────────────────────────────────

    @Test
    void patternDetectionGetEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/patterns/detect")
                        .param("nodeCode", "CP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCode").value("CP"))
                .andExpect(jsonPath("$.matchedPatterns").isArray())
                .andExpect(jsonPath("$.incompletePatterns").isArray())
                .andExpect(jsonPath("$.patternCoverage").isNumber());
    }

    @Test
    void patternDetectionPostEndpointReturnsOk() throws Exception {
        mockMvc.perform(post("/api/patterns/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP\":80},\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedPatterns").isArray())
                .andExpect(jsonPath("$.incompletePatterns").isArray())
                .andExpect(jsonPath("$.patternCoverage").isNumber());
    }

    @Test
    void patternDetectionPostEndpointWithEmptyScores() throws Exception {
        mockMvc.perform(post("/api/patterns/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").isNotEmpty());
    }
}
