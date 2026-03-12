package com.taxonomy;

import com.taxonomy.dto.ArchiMateImportResult;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.EnrichedChangeImpactView;
import com.taxonomy.service.ArchitectureGapService;
import com.taxonomy.service.ArchitecturePatternService;
import com.taxonomy.service.ArchitectureRecommendationService;
import com.taxonomy.service.ArchiMateXmlImporter;
import com.taxonomy.service.EnrichedImpactService;
import com.taxonomy.repository.TaxonomyRelationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the Architecture Intelligence features:
 * - Gap Analysis (PR 2)
 * - Enriched Failure Impact (PR 3)
 * - Pattern Detection (PR 4)
 * - Architecture Recommendation (PR 5)
 * - ArchiMate Import (PR 6)
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArchitectureIntelligenceTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ArchitectureGapService gapService;
    @Autowired private ArchitecturePatternService patternService;
    @Autowired private ArchitectureRecommendationService recommendationService;
    @Autowired private EnrichedImpactService enrichedImpactService;
    @Autowired private ArchiMateXmlImporter archiMateXmlImporter;
    @Autowired private TaxonomyRelationRepository relationRepository;

    @BeforeEach
    void cleanRelations() {
        // Keep CSV-loaded relations, just ensure clean state for imports
    }

    // ── Gap Analysis Tests ────────────────────────────────────────────────────

    @Test
    void gapAnalysisWithEmptyScoresReturnsEmptyResult() {
        GapAnalysisView view = gapService.analyze(Map.of(), "test requirement", 50);

        assertThat(view.getBusinessText()).isEqualTo("test requirement");
        assertThat(view.getTotalAnchors()).isZero();
        assertThat(view.getMissingRelations()).isEmpty();
    }

    @Test
    void gapAnalysisWithHighScoredNodeIdentifiesGaps() {
        Map<String, Integer> scores = Map.of("CP-1010", 90, "BP-1010", 80);
        GapAnalysisView view = gapService.analyze(scores, "secure communications", 50);

        assertThat(view.getTotalAnchors()).isGreaterThan(0);
        assertThat(view.getBusinessText()).isEqualTo("secure communications");
        // Should find gaps since individual nodes may not have all expected relations
        assertThat(view.getNotes()).isNotNull();
    }

    @Test
    void gapAnalysisEndpointReturnsOk() throws Exception {
        mockMvc.perform(post("/api/gap/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP-1010\":90},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessText").value("test"))
                .andExpect(jsonPath("$.totalAnchors").isNumber())
                .andExpect(jsonPath("$.missingRelations").isArray())
                .andExpect(jsonPath("$.incompletePatterns").isArray())
                .andExpect(jsonPath("$.coverageGaps").isArray());
    }

    @Test
    void gapAnalysisWithNullScoresReturnsEmpty() throws Exception {
        mockMvc.perform(post("/api/gap/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":null,\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAnchors").value(0));
    }

    // ── Pattern Detection Tests ───────────────────────────────────────────────

    @Test
    void patternDetectionWithEmptyScoresReturnsEmptyView() {
        PatternDetectionView view = patternService.detectForScores(Map.of(), 50);

        assertThat(view.getMatchedPatterns()).isEmpty();
        assertThat(view.getIncompletePatterns()).isEmpty();
    }

    @Test
    void patternDetectionForSpecificNodeReturnsView() {
        PatternDetectionView view = patternService.detectForNode("CP-1010");

        assertThat(view.getNodeCode()).isEqualTo("CP-1010");
        assertThat(view.getNotes()).isNotNull();
    }

    @Test
    void patternDetectionEndpointReturnsOk() throws Exception {
        mockMvc.perform(post("/api/patterns/detect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP-1010\":90,\"BP-1010\":80},\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedPatterns").isArray())
                .andExpect(jsonPath("$.incompletePatterns").isArray())
                .andExpect(jsonPath("$.patternCoverage").isNumber());
    }

    @Test
    void patternDetectionForNodeEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/patterns/detect")
                        .param("nodeCode", "CP-1010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCode").value("CP-1010"));
    }

    // ── Architecture Recommendation Tests ─────────────────────────────────────

    @Test
    void recommendationWithEmptyScoresReturnsNotes() {
        ArchitectureRecommendation rec = recommendationService.recommend(
                Map.of(), "test requirement", 50);

        assertThat(rec.getBusinessText()).isEqualTo("test requirement");
        assertThat(rec.getNotes()).isNotEmpty();
    }

    @Test
    void recommendationWithScoresReturnsElements() {
        Map<String, Integer> scores = Map.of("CP-1010", 90, "BP-1010", 80);
        ArchitectureRecommendation rec = recommendationService.recommend(
                scores, "secure voice communications", 50);

        assertThat(rec.getBusinessText()).isEqualTo("secure voice communications");
        assertThat(rec.getConfirmedElements()).isNotNull();
        assertThat(rec.getProposedElements()).isNotNull();
        assertThat(rec.getSuggestedRelations()).isNotNull();
        assertThat(rec.getReasoning()).isNotNull();
    }

    @Test
    void recommendationEndpointReturnsOk() throws Exception {
        mockMvc.perform(post("/api/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scores\":{\"CP-1010\":90},\"businessText\":\"test\",\"minScore\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessText").value("test"))
                .andExpect(jsonPath("$.confirmedElements").isArray())
                .andExpect(jsonPath("$.proposedElements").isArray())
                .andExpect(jsonPath("$.suggestedRelations").isArray())
                .andExpect(jsonPath("$.confidence").isNumber())
                .andExpect(jsonPath("$.reasoning").isArray());
    }

    // ── Enriched Failure Impact Tests ─────────────────────────────────────────

    @Test
    void enrichedFailureImpactReturnsViewWithRiskScore() {
        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact("BP-1010", 2);

        assertThat(view.getFailedNodeCode()).isEqualTo("BP-1010");
        assertThat(view.getMaxHops()).isEqualTo(2);
        assertThat(view.getRiskScore()).isGreaterThanOrEqualTo(0.0);
        assertThat(view.getDirectlyAffected()).isNotNull();
        assertThat(view.getIndirectlyAffected()).isNotNull();
    }

    @Test
    void enrichedFailureImpactEndpointReturnsOk() throws Exception {
        mockMvc.perform(get("/api/graph/node/BP-1010/enriched-failure-impact")
                        .param("maxHops", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedNodeCode").value("BP-1010"))
                .andExpect(jsonPath("$.maxHops").value(2))
                .andExpect(jsonPath("$.riskScore").isNumber())
                .andExpect(jsonPath("$.directlyAffected").isArray())
                .andExpect(jsonPath("$.indirectlyAffected").isArray())
                .andExpect(jsonPath("$.affectedRequirements").isArray());
    }

    // ── ArchiMate Import Tests ────────────────────────────────────────────────

    @Test
    void archiMateImportParsesValidXml() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       identifier="id-model-1">
                  <name xml:lang="en">Test Model</name>
                  <elements>
                    <element identifier="id-e1" xsi:type="Capability">
                      <name xml:lang="en">Test Capability</name>
                    </element>
                    <element identifier="id-e2" xsi:type="ApplicationService">
                      <name xml:lang="en">Test Service</name>
                    </element>
                  </elements>
                  <relationships>
                    <relationship identifier="id-r1" xsi:type="Realization"
                                  source="id-e1" target="id-e2">
                      <name xml:lang="en">realizes</name>
                    </relationship>
                  </relationships>
                </model>
                """;

        ArchiMateImportResult result = archiMateXmlImporter.importXml(
                new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getElementsImported()).isEqualTo(2);
        assertThat(result.getNotes()).isNotEmpty();
    }

    @Test
    void archiMateImportEndpointReturnsOk() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       identifier="id-model-1">
                  <name xml:lang="en">Test Model</name>
                  <elements>
                    <element identifier="id-e1" xsi:type="Capability">
                      <name xml:lang="en">Test Capability</name>
                    </element>
                  </elements>
                  <relationships/>
                </model>
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "test-model.xml",
                MediaType.APPLICATION_XML_VALUE,
                xml.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/import/archimate").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.elementsImported").isNumber())
                .andExpect(jsonPath("$.notes").isArray());
    }

    @Test
    void archiMateImportWithEmptyFileReturnsBadRequest() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.xml",
                MediaType.APPLICATION_XML_VALUE,
                new byte[0]);

        mockMvc.perform(multipart("/api/import/archimate").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archiMateImportHandlesUnknownTypes() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <model xmlns="http://www.opengroup.org/xsd/archimate/3.0/"
                       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       identifier="id-model-1">
                  <name xml:lang="en">Test</name>
                  <elements>
                    <element identifier="id-e1" xsi:type="UnknownType">
                      <name xml:lang="en">Unknown</name>
                    </element>
                  </elements>
                  <relationships/>
                </model>
                """;

        ArchiMateImportResult result = archiMateXmlImporter.importXml(
                new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.getElementsImported()).isEqualTo(1);
        assertThat(result.getNotes().stream()
                .anyMatch(n -> n.contains("Unknown ArchiMate type"))).isTrue();
    }
}
