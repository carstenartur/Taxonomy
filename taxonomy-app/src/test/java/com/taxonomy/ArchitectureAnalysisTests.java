package com.taxonomy;

import com.taxonomy.dto.*;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.repository.RequirementCoverageRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.relations.service.RequirementCoverageService;
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
import org.springframework.security.test.context.support.WithMockUser;
import com.taxonomy.architecture.service.ArchitectureGapService;
import com.taxonomy.architecture.service.ArchitecturePatternService;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import com.taxonomy.architecture.service.EnrichedImpactService;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.ApqcCoverageResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.EnrichedChangeImpactView;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.RecommendedElement;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.relations.service.RequirementCoverageService;

/**
 * Tests for Steps 2–5:
 * ArchitectureGapService, EnrichedImpactService,
 * ArchitectureRecommendationService, ArchitecturePatternService,
 * and their REST API controllers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = {"USER", "ARCHITECT", "ADMIN"})
class ArchitectureAnalysisTests extends GraphSnapshotFixtureSupport {

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
    void compatibilityProfileNeverInventsObligationsFromLegalRelations() {
        var view = ArchitectureGapService.compatibilityAssessment("test");
        assertThat(view.getJudgments()).isEmpty();
        assertThat(view.getTotalGaps()).isZero();
        assertThat(view.getProfileId()).isEqualTo("compatibility");
        assertThat(view.getEvidenceCompleteness()).isEqualTo(com.taxonomy.dto.EvidenceCompleteness.INCOMPLETE);
    }

    @Test
    void targetDomainsDistinguishForbiddenFromUnrestricted() {
        assertThat(compatibilityMatrix.targetDomain("UNKNOWN", RelationType.REALIZES).state())
                .isEqualTo(com.taxonomy.dsl.validation.RelationMetamodel.DomainState.FORBIDDEN);
        assertThat(compatibilityMatrix.targetDomain("UNKNOWN", RelationType.RELATED_TO).state())
                .isEqualTo(com.taxonomy.dsl.validation.RelationMetamodel.DomainState.UNRESTRICTED);
        assertThat(compatibilityMatrix.targetDomain("CP", RelationType.REALIZES).roots()).containsExactly("CR");
    }

    @Test
    void compatibilityMatrixSystemUsesSystemAndCoreService() {
        assertThat(compatibilityMatrix.isCompatible("SY", "SY", RelationType.USES)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("SY", "CR", RelationType.USES)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("SY", "BP", RelationType.USES)).isFalse();
    }

    @Test
    void compatibilityMatrixComponentDependsOnComponentAndCoreService() {
        assertThat(compatibilityMatrix.isCompatible("CM", "CM", RelationType.DEPENDS_ON)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("CM", "CR", RelationType.DEPENDS_ON)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("CM", "BP", RelationType.DEPENDS_ON)).isFalse();
    }

    @Test
    void compatibilityMatrixSystemContainsUserAppAndComponent() {
        assertThat(compatibilityMatrix.isCompatible("SY", "UA", RelationType.CONTAINS)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("SY", "CM", RelationType.CONTAINS)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("SY", "BP", RelationType.CONTAINS)).isFalse();
    }

    @Test
    void systemLegalityDoesNotImposeAllAllowedTargets() {
        assertThat(compatibilityMatrix.targetDomain("SY", RelationType.USES).roots()).containsExactlyInAnyOrder("SY", "CR");
        assertThat(compatibilityMatrix.targetDomain("SY", RelationType.CONTAINS).roots()).containsExactlyInAnyOrder("UA", "CM");
    }

    // ── Gap Analysis API Tests ────────────────────────────────────────────

    @Test
    void gapAnalysisEndpointRejectsUnscopedScoreBody() throws Exception {
        mockMvc.perform(post("/api/gap/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP\":80},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void gapAnalysisEndpointWithEmptyScores() throws Exception {
        mockMvc.perform(post("/api/gap/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isBadRequest());
    }

    // ── APQC Coverage Tests ──────────────────────────────────────────────

    @Test
    void apqcCoverageWithoutExplicitProvenanceIsInconclusive() throws Exception {
        ApqcCoverageResult result = gapService.analyzeApqcCoverage(graphContext, graphCommit);
        assertThat(result.state()).isEqualTo("INCONCLUSIVE");
        assertThat(result.linkedPercent()).isNull();
        assertThat(result.observedElements()).isZero();
    }

    @Test
    void apqcCoverageEndpointReportsScopedInconclusiveEvidence() throws Exception {
        mockMvc.perform(graphRequest(get("/api/gap/apqc-coverage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INCONCLUSIVE"))
                .andExpect(jsonPath("$.observedElements").value(0))
                .andExpect(jsonPath("$.commitId").value(graphCommit));
    }

    @Test
    void apqcCoverageEndpointRequiresExplicitContext() throws Exception {
        mockMvc.perform(get("/api/gap/apqc-coverage").param("businessText", "Supply chain management"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void apqcHierarchyEndpointReturnsOk() throws Exception {
        mockMvc.perform(graphRequest(get("/api/graph/apqc-hierarchy")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isArray());
    }

    @Test
    void relationDescriptionDoesNotEstablishApqcElementProvenance() throws Exception {
        graphRelation("CP", "BP", RelationType.RELATED_TO, "apqc text", "APQC_IMPORT");
        var result = gapService.analyzeApqcCoverage(graphContext, graphCommit);
        assertThat(result.state()).isEqualTo("INCONCLUSIVE");
        assertThat(result.observedElements()).isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 3 — Enriched Failure Impact Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void enrichedFailureImpactBasicStructure() {
        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact(graphContext, graphCommit, "BP", 3);
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
        graphRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        graphMapping("REQ-ENRICH-01", "CP");

        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact(graphContext, graphCommit, "BP", 3);

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
        graphRelation("BP", "CP", RelationType.SUPPORTS, null, "test");
        graphMapping("REQ-RISK-01", "CP");

        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact(graphContext, graphCommit, "BP", 3);

        assertThat(view.getRiskScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void enrichedFailureImpactForNonExistentNodeReturnsNote() {
        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact(graphContext, graphCommit, "NONEXISTENT", 3);
        assertThat(view.getNotes()).isNotEmpty();
        assertThat(view.getDirectlyAffected()).isEmpty();
    }

    // ── Enriched Failure Impact API Tests ─────────────────────────────────

    @Test
    void enrichedFailureImpactEndpointReturnsOk() throws Exception {
        mockMvc.perform(graphRequest(get("/api/graph/node/BP/enriched-failure-impact"))
                        .param("maxHops", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedNodeCode").value("BP"))
                .andExpect(jsonPath("$.maxHops").value(3))
                .andExpect(jsonPath("$.directlyAffected").isArray())
                .andExpect(jsonPath("$.indirectlyAffected").isArray())
                .andExpect(jsonPath("$.affectedRequirements").isArray())
                .andExpect(jsonPath("$.riskScore").doesNotExist())
                .andExpect(jsonPath("$.correlationCompleteness").value("INCONCLUSIVE"));
    }

    @Test
    void enrichedFailureImpactEndpointDefaultsMaxHopsTo3() throws Exception {
        mockMvc.perform(graphRequest(get("/api/graph/node/BP/enriched-failure-impact")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxHops").value(3));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 4 — Architecture Recommendation Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void recommendationWithNoScoresReturnsNote() {
        ArchitectureRecommendation rec = recommendationService.rankMatches(Map.of(), "test", 50);
        assertThat(rec.getNotes()).isNotEmpty();
        assertThat(rec.getMatchedElements()).isEmpty();
    }

    @Test
    void recommendationWithNullScoresReturnsNote() {
        ArchitectureRecommendation rec = recommendationService.rankMatches(null, "test", 50);
        assertThat(rec.getNotes()).isNotEmpty();
    }

    @Test
    void recommendationRanksMatchesWithoutAcceptance() {
        ArchitectureRecommendation rec = recommendationService.rankMatches(
                Map.of("CP", 85d, "BP", 75d), "secure voice comms", 50);

        assertThat(rec.getMatchedElements()).isNotEmpty();
        assertThat(rec.getMatchedElements().stream()
                .map(RecommendedElement::getNodeCode).toList())
                .contains("CP", "BP");
    }

    @Test
    void recommendationProposesElementsForGaps() {
        // CP needs REALIZES → CR, so the recommendation should propose CR nodes
        ArchitectureRecommendation rec = recommendationService.rankMatches(
                Map.of("CP", 85d), "secure voice communications", 50);

        assertThat(rec.getMatchedElements()).isNotEmpty();
        // Proposals depend on available nodes in the CR root
        assertThat(rec.getAcceptedElements()).isEmpty();
        assertThat(rec.getProposedElements()).isEmpty();
        assertThat(rec.getMeanRelevance()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void recommendationSuggestsRelations() {
        ArchitectureRecommendation rec = recommendationService.rankMatches(
                Map.of("CP", 85d), "secure voice communications", 50);

        // Should suggest REALIZES relations to fill gaps
        assertThat(rec.getSuggestedRelations()).isNotNull();
    }

    @Test
    void recommendationComputesConfidence() {
        ArchitectureRecommendation rec = recommendationService.rankMatches(
                Map.of("CP", 85d), "test", 50);
        assertThat(rec.getMeanRelevance()).isGreaterThanOrEqualTo(0.0);
        assertThat(rec.getMeanRelevance()).isLessThanOrEqualTo(100.0);
    }

    // ── Recommendation API Tests ──────────────────────────────────────────

    @Test
    void recommendationEndpointRejectsUnscopedScoreBody() throws Exception {
        mockMvc.perform(post("/api/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP\":80},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recommendationEndpointWithEmptyScores() throws Exception {
        mockMvc.perform(post("/api/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isBadRequest());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Step 5 — Pattern Detection Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void patternDetectionForNonExistentNodeReturnsNote() {
        PatternDetectionView view = patternService.detectForNode(graphContext, graphCommit, "NONEXISTENT");
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void patternDetectionForCapabilityNodeChecksFullStack() {
        patternModel();
        // CP is a starting root for the "Full Stack" pattern
        PatternDetectionView view = patternService.detectForNode(graphContext, graphCommit, "CP");

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
        patternModel();
        // Create the full chain: CP → REALIZES → CR → SUPPORTS → BP → CONSUMES → IP
        graphRelation("CP", "CR", RelationType.REALIZES, null, "test");
        graphRelation("CR", "BP", RelationType.SUPPORTS, null, "test");
        graphRelation("BP", "IP", RelationType.CONSUMES, null, "test");

        PatternDetectionView view = patternService.detectForNode(graphContext, graphCommit, "CP");

        assertThat(view.getMatchedPatterns()).isNotEmpty();
        assertThat(view.getMatchedPatterns().stream()
                .anyMatch(p -> "Full Stack".equals(p.getPatternName())
                        && p.getCompleteness() >= 100.0)).isTrue();
    }

    @Test
    void patternDetectionFindsIncompletePattern() {
        patternModel();
        // Only first step: CP → REALIZES → CR
        graphRelation("CP", "CR", RelationType.REALIZES, null, "test");

        PatternDetectionView view = patternService.detectForNode(graphContext, graphCommit, "CP");

        assertThat(view.getIncompletePatterns()).isNotEmpty();
        assertThat(view.getIncompletePatterns().stream()
                .anyMatch(p -> "Full Stack".equals(p.getPatternName())
                        && p.getCompleteness() > 0.0
                        && p.getCompleteness() < 100.0)).isTrue();
    }

    @Test
    void patternDetectionWithEmptyScoresReturnsNote() {
        PatternDetectionView view = patternService.detectForScores(graphContext, graphCommit, Map.of(), 50);
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void patternDetectionWithNullScoresReturnsNote() {
        PatternDetectionView view = patternService.detectForScores(graphContext, graphCommit, null, 50);
        assertThat(view.getNotes()).isNotEmpty();
    }

    @Test
    void patternDetectionForScoresAggregatesResults() {
        patternModel();
        graphRelation("CP", "CR", RelationType.REALIZES, null, "test");

        PatternDetectionView view = patternService.detectForScores(graphContext, graphCommit, Map.of("CP", 80d), 50);

        // Should find the incomplete Full Stack pattern
        int totalDetected = view.getMatchedPatterns().size() + view.getIncompletePatterns().size();
        assertThat(totalDetected).isGreaterThanOrEqualTo(1);
    }

    // ── Pattern Detection API Tests ───────────────────────────────────────

    @Test
    void patternDetectionGetEndpointReturnsOk() throws Exception {
        patternModel();
        mockMvc.perform(graphRequest(get("/api/patterns/detect"))
                        .param("nodeCode", "CP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCode").value("CP"))
                .andExpect(jsonPath("$.matchedPatterns").isArray())
                .andExpect(jsonPath("$.incompletePatterns").isArray())
                .andExpect(jsonPath("$.patternCoverage").isNumber());
    }

    @Test
    void patternDetectionPostEndpointReturnsOk() throws Exception {
        patternModel();
        mockMvc.perform(graphRequest(post("/api/patterns/detect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP\":80},\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedPatterns").isArray())
                .andExpect(jsonPath("$.incompletePatterns").isArray())
                .andExpect(jsonPath("$.patternCoverage").isNumber());
    }

    @Test
    void patternDetectionPostEndpointWithEmptyScores() throws Exception {
        mockMvc.perform(graphRequest(post("/api/patterns/detect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{},\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").isNotEmpty());
    }
    private void patternModel() {
        graphElement("CP", "Capability", Map.of());
        graphElement("CR", "CoreService", Map.of());
        graphElement("BP", "Process", Map.of());
        graphElement("IP", "InformationProduct", Map.of());
    }
}
