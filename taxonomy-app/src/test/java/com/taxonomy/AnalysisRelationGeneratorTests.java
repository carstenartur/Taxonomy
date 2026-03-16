package com.taxonomy;

import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.analysis.service.AnalysisRelationGenerator;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.test.context.support.WithMockUser;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class AnalysisRelationGeneratorTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnalysisRelationGenerator generator;

    @Autowired
    private RequirementArchitectureViewService architectureViewService;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    @BeforeEach
    void cleanRelations() {
        relationRepository.deleteAll();
    }

    // ── Generator Unit Tests ────────────────────────────────────────────────

    @Test
    void generateReturnsEmptyForNullScores() {
        List<RelationHypothesisDto> result = generator.generate(null);
        assertThat(result).isEmpty();
    }

    @Test
    void generateReturnsEmptyForEmptyScores() {
        List<RelationHypothesisDto> result = generator.generate(Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void generateReturnsEmptyWhenAllScoresBelowThreshold() {
        Map<String, Integer> scores = Map.of("BP", 30, "CP", 20);
        List<RelationHypothesisDto> result = generator.generate(scores);
        assertThat(result).isEmpty();
    }

    @Test
    void generateReturnsEmptyWhenOnlyOneRootQualifies() {
        // Only BP root qualifies — need at least 2 roots for cross-root relations
        Map<String, Integer> scores = Map.of("BP", 80, "BP-1327", 70);
        List<RelationHypothesisDto> result = generator.generate(scores);
        assertThat(result).isEmpty();
    }

    @Test
    void generateProducesHypothesesForCrossRootScores() {
        // CP and CR nodes both score high — compatibility matrix says CP→CR via REALIZES
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("CP", 80);
        scores.put("CR", 70);

        List<RelationHypothesisDto> result = generator.generate(scores);

        assertThat(result).isNotEmpty();
        // Should contain at least CP→CR REALIZES
        assertThat(result).anyMatch(h ->
                h.getSourceCode().equals("CP") &&
                h.getTargetCode().equals("CR") &&
                h.getRelationType().equals("REALIZES"));
    }

    @Test
    void generateSetsConfidenceFromScoreProduct() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("CP", 80);
        scores.put("CR", 70);

        List<RelationHypothesisDto> result = generator.generate(scores);

        RelationHypothesisDto cpCr = result.stream()
                .filter(h -> "CP".equals(h.getSourceCode()) && "CR".equals(h.getTargetCode())
                        && "REALIZES".equals(h.getRelationType()))
                .findFirst()
                .orElse(null);

        assertThat(cpCr).isNotNull();
        // confidence = (80 × 70) / 10000 = 0.56
        assertThat(cpCr.getConfidence()).isCloseTo(0.56, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void generateSortsResultsByConfidenceDescending() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 90);
        scores.put("CP", 80);
        scores.put("CR", 70);
        scores.put("IP", 60);

        List<RelationHypothesisDto> result = generator.generate(scores);

        // Should be sorted by confidence descending
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).getConfidence())
                    .isLessThanOrEqualTo(result.get(i - 1).getConfidence());
        }
    }

    @Test
    void generateIncludesReasoningWithNodeNames() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("CP", 80);
        scores.put("CR", 70);

        List<RelationHypothesisDto> result = generator.generate(scores);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getReasoning()).contains("compatibility matrix");
    }

    @Test
    void generateUsesMultipleRelationTypes() {
        // BP, CP, CR, IP all score high — multiple relation types should appear
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("BP", 90);
        scores.put("CP", 85);
        scores.put("CR", 80);
        scores.put("IP", 75);

        List<RelationHypothesisDto> result = generator.generate(scores);

        // Should have multiple distinct relation types
        long distinctTypes = result.stream()
                .map(RelationHypothesisDto::getRelationType)
                .distinct().count();
        assertThat(distinctTypes).isGreaterThan(1);
    }

    // ── Architecture View with Provisional Relations ────────────────────────

    @Test
    void architectureViewUsesProvisionalRelationsWhenNoConfirmedExist() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("CP", 80);
        scores.put("CR", 70);
        scores.put("BP", 75);

        List<RelationHypothesisDto> provisional = generator.generate(scores);

        RequirementArchitectureView view = architectureViewService.build(
                scores, "test", 0, provisional);

        // Should have relationships from provisional relations
        assertThat(view.getIncludedRelationships()).isNotEmpty();
        assertThat(view.getIncludedRelationships()).anyMatch(
                r -> "provisional (AI-suggested, not yet confirmed)".equals(r.getIncludedBecause()));
        assertThat(view.getNotes()).anyMatch(n -> n.contains("provisional"));
    }

    @Test
    void architectureViewWithoutProvisionalReturnsOnlyDirectMatches() {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("CP", 80);
        scores.put("CR", 70);
        scores.put("BP", 75);

        RequirementArchitectureView view = architectureViewService.build(
                scores, "test", 0, null);

        // Without provisional relations and no confirmed relations, should have no relationships
        assertThat(view.getIncludedRelationships()).isEmpty();
        assertThat(view.getNotes()).anyMatch(n -> n.contains("No traversable relations"));
    }

    // ── API Integration Tests ───────────────────────────────────────────────

    @Test
    void analyzeEndpointIncludesProvisionalRelations() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice communications for deployed military forces\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.provisionalRelations").isArray());
    }

    @Test
    void analyzeWithArchViewUsesProvisionalRelations() throws Exception {
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessText\":\"Provide secure voice communications for deployed military forces\",\"includeArchitectureView\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores").exists())
                .andExpect(jsonPath("$.provisionalRelations").isArray())
                .andExpect(jsonPath("$.architectureView").exists())
                .andExpect(jsonPath("$.architectureView.notes").isArray());
    }
}
