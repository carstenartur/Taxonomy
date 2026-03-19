package com.taxonomy;

import com.taxonomy.dto.SavedAnalysis;
import com.taxonomy.analysis.service.SavedAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SavedAnalysisService}.
 */
@SpringBootTest
class SavedAnalysisServiceTest {

    @Autowired
    private SavedAnalysisService savedAnalysisService;

    // ── buildExport ───────────────────────────────────────────────────────────

    @Test
    void buildExportPopulatesAllFields() {
        Map<String, Integer> scores  = Map.of("CO", 35, "CR", 25, "BR", 0);
        Map<String, String>  reasons = Map.of("CO", "Voice comms", "CR", "Core services");

        SavedAnalysis saved = savedAnalysisService.buildExport(
                "Provide secure voice comms", scores, reasons, "GEMINI");

        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getRequirement()).isEqualTo("Provide secure voice comms");
        assertThat(saved.getTimestamp()).isNotBlank();
        assertThat(saved.getProvider()).isEqualTo("GEMINI");
        assertThat(saved.getScores()).containsEntry("CO", 35);
        assertThat(saved.getScores()).containsEntry("BR", 0);
        assertThat(saved.getReasons()).containsEntry("CO", "Voice comms");
    }

    // ── importFromJson ────────────────────────────────────────────────────────

    @Test
    void importRoundTripPreservesAllFields() throws IOException {
        Map<String, Integer> scores  = Map.of("CO", 35, "CR", 25, "BR", 0);
        Map<String, String>  reasons = Map.of("CO", "Voice comms");

        SavedAnalysis exported = savedAnalysisService.buildExport(
                "Secure voice comms requirement", scores, reasons, "GEMINI");

        // Serialize manually
        String json = "{\"version\":1,\"requirement\":\"Secure voice comms requirement\","
                + "\"timestamp\":\"2026-03-08T14:30:00Z\",\"provider\":\"GEMINI\","
                + "\"scores\":{\"CO\":35,\"CR\":25,\"BR\":0},"
                + "\"reasons\":{\"CO\":\"Voice comms\"}}";

        SavedAnalysis imported = savedAnalysisService.importFromJson(json);

        assertThat(imported.getVersion()).isEqualTo(1);
        assertThat(imported.getRequirement()).isEqualTo("Secure voice comms requirement");
        assertThat(imported.getProvider()).isEqualTo("GEMINI");
        assertThat(imported.getScores()).containsEntry("CO", 35);
        assertThat(imported.getScores()).containsEntry("CR", 25);
        // 0 must be preserved (not missing)
        assertThat(imported.getScores()).containsEntry("BR", 0);
        assertThat(imported.getReasons()).containsEntry("CO", "Voice comms");
    }

    @Test
    void importPreservesZeroVsAbsentDistinction() throws IOException {
        String json = "{\"version\":1,\"requirement\":\"Test requirement\","
                + "\"scores\":{\"CO\":35,\"BR\":0},"
                + "\"reasons\":{}}";

        SavedAnalysis imported = savedAnalysisService.importFromJson(json);

        // BR=0 must be PRESENT in the map (evaluated, scored zero)
        assertThat(imported.getScores()).containsKey("BR");
        assertThat(imported.getScores().get("BR")).isEqualTo(0);

        // UA is absent — was not evaluated at all
        assertThat(imported.getScores()).doesNotContainKey("UA");
    }

    @Test
    void importRejectsUnsupportedVersion() {
        String json = "{\"version\":99,\"requirement\":\"Test\","
                + "\"scores\":{\"CO\":35},\"reasons\":{}}";

        assertThatThrownBy(() -> savedAnalysisService.importFromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported version");
    }

    @Test
    void importRejectsBlankRequirement() {
        String json = "{\"version\":1,\"requirement\":\"\","
                + "\"scores\":{\"CO\":35},\"reasons\":{}}";

        assertThatThrownBy(() -> savedAnalysisService.importFromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirement");
    }

    @Test
    void importRejectsNullRequirement() {
        String json = "{\"version\":1,\"scores\":{\"CO\":35},\"reasons\":{}}";

        assertThatThrownBy(() -> savedAnalysisService.importFromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirement");
    }

    @Test
    void importRejectsEmptyScores() {
        String json = "{\"version\":1,\"requirement\":\"Test requirement\","
                + "\"scores\":{},\"reasons\":{}}";

        assertThatThrownBy(() -> savedAnalysisService.importFromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scores");
    }

    @Test
    void importRejectsNullScores() {
        String json = "{\"version\":1,\"requirement\":\"Test requirement\",\"reasons\":{}}";

        assertThatThrownBy(() -> savedAnalysisService.importFromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scores");
    }

    @Test
    void importWarnsOnUnknownNodeCodesButDoesNotFail() throws IOException {
        String json = "{\"version\":1,\"requirement\":\"Test requirement\","
                + "\"scores\":{\"CO\":35,\"UNKNOWN_XYZ\":10},\"reasons\":{}}";

        // Should NOT throw — unknown codes generate warnings but are accepted
        SavedAnalysis imported = savedAnalysisService.importFromJson(json);
        assertThat(imported.getScores()).containsEntry("CO", 35);
        assertThat(imported.getScores()).containsEntry("UNKNOWN_XYZ", 10);

        // findUnknownCodes should report the unknown code
        assertThat(savedAnalysisService.findUnknownCodes(imported))
                .contains("UNKNOWN_XYZ")
                .doesNotContain("CO");
    }

    // ── loadFromClasspath ──────────────────────────────────────────────────────

    @Test
    void loadFromClasspathLoadsSecureVoiceComms() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/secure-voice-comms.json");

        assertThat(saved).isNotNull();
        assertThat(saved.getRequirement()).isNotBlank();
        assertThat(saved.getScores()).isNotEmpty();
        assertThat(saved.getScores()).containsKey("CO");
        assertThat(saved.getScores()).containsKey("BR");
        // BR=0 must be present (evaluated, scored zero)
        assertThat(saved.getScores().get("BR")).isEqualTo(0);
    }

    @Test
    void loadFromClasspathLoadsLogisticsSupplyChain() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/logistics-supply-chain.json");

        assertThat(saved).isNotNull();
        assertThat(saved.getRequirement()).isNotBlank();
        assertThat(saved.getScores()).isNotEmpty();
        assertThat(saved.getScores()).containsKey("BP");
    }

    @Test
    void loadFromClasspathLoadsCyberDefence() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/cyber-defence-monitoring.json");

        assertThat(saved).isNotNull();
        assertThat(saved.getRequirement()).isNotBlank();
        assertThat(saved.getScores()).isNotEmpty();
        assertThat(saved.getScores()).containsKey("CO");
    }

    @Test
    void findUnknownCodesReturnsEmptyForValidCodes() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/secure-voice-comms.json");

        assertThat(savedAnalysisService.findUnknownCodes(saved)).isEmpty();
    }

    // ── Independent scoring model validation ──────────────────────────────────

    private static final List<String> ROOT_CODES = List.of("BP", "BR", "CI", "CO", "CP", "CR", "IP", "UA");

    @Test
    void secureVoiceCommsRootScoresSumExceedsOneHundred() throws IOException {
        // Root taxonomies are scored INDEPENDENTLY (0–100 each), not as a pie chart.
        // The sum of the 8 root scores MUST be > 100 to confirm the independent model.
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/secure-voice-comms.json");

        int sum = ROOT_CODES.stream()
                .mapToInt(r -> saved.getScores().getOrDefault(r, 0))
                .sum();
        assertThat(sum).as("Sum of 8 root scores must exceed 100 (independent per-taxonomy model)")
                .isGreaterThan(100);
    }

    @Test
    void logisticsSupplyChainRootScoresSumExceedsOneHundred() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/logistics-supply-chain.json");

        int sum = ROOT_CODES.stream()
                .mapToInt(r -> saved.getScores().getOrDefault(r, 0))
                .sum();
        assertThat(sum).as("Sum of 8 root scores must exceed 100 (independent per-taxonomy model)")
                .isGreaterThan(100);
    }

    @Test
    void cyberDefenceRootScoresSumExceedsOneHundred() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/cyber-defence-monitoring.json");

        int sum = ROOT_CODES.stream()
                .mapToInt(r -> saved.getScores().getOrDefault(r, 0))
                .sum();
        assertThat(sum).as("Sum of 8 root scores must exceed 100 (independent per-taxonomy model)")
                .isGreaterThan(100);
    }

    @Test
    void secureVoiceCommsDominantRootIsCo() throws IOException {
        // For a voice comms requirement, CO must be the highest-scoring root taxonomy
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/secure-voice-comms.json");

        int coScore  = saved.getScores().getOrDefault("CO", 0);
        int maxScore = ROOT_CODES.stream()
                .mapToInt(r -> saved.getScores().getOrDefault(r, 0))
                .max().orElse(0);
        assertThat(coScore).as("CO must be the dominant root taxonomy for a voice comms requirement")
                .isEqualTo(maxScore);
    }

    @Test
    void logisticsSupplyChainDominantRootIsBp() throws IOException {
        // For a logistics requirement, BP must be the highest-scoring root taxonomy
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/logistics-supply-chain.json");

        int bpScore  = saved.getScores().getOrDefault("BP", 0);
        int maxScore = ROOT_CODES.stream()
                .mapToInt(r -> saved.getScores().getOrDefault(r, 0))
                .max().orElse(0);
        assertThat(bpScore).as("BP must be the dominant root taxonomy for a logistics requirement")
                .isEqualTo(maxScore);
    }

    @Test
    void cyberDefenceDominantRootIsCo() throws IOException {
        // For a cyber defence requirement, CO must be the highest-scoring root taxonomy
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/cyber-defence-monitoring.json");

        int coScore  = saved.getScores().getOrDefault("CO", 0);
        int maxScore = ROOT_CODES.stream()
                .mapToInt(r -> saved.getScores().getOrDefault(r, 0))
                .max().orElse(0);
        assertThat(coScore).as("CO must be the dominant root taxonomy for a cyber defence requirement")
                .isEqualTo(maxScore);
    }

    @Test
    void secureVoiceCommsContainsAllTaxonomyNodes() throws IOException {
        // The mock JSON must contain scores for ALL taxonomy nodes (roots + full hierarchy)
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/secure-voice-comms.json");

        assertThat(saved.getScores().size())
                .as("Mock JSON must contain scores for the full taxonomy tree (not only the 8 roots)")
                .isGreaterThan(2500);
    }

    @Test
    void logisticsSupplyChainContainsAllTaxonomyNodes() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/logistics-supply-chain.json");

        assertThat(saved.getScores().size())
                .as("Mock JSON must contain scores for the full taxonomy tree (not only the 8 roots)")
                .isGreaterThan(2500);
    }

    @Test
    void cyberDefenceContainsAllTaxonomyNodes() throws IOException {
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/cyber-defence-monitoring.json");

        assertThat(saved.getScores().size())
                .as("Mock JSON must contain scores for the full taxonomy tree (not only the 8 roots)")
                .isGreaterThan(2500);
    }

    @Test
    void secureVoiceCommsBrRootAndAllChildrenAreZero() throws IOException {
        // BR has root score 0 → all BR children must also be 0 (not relevant taxonomy)
        SavedAnalysis saved = savedAnalysisService.loadFromClasspath(
                "mock-scores/secure-voice-comms.json");

        assertThat(saved.getScores().getOrDefault("BR", -1)).isEqualTo(0);
        long nonZeroBrNodes = saved.getScores().entrySet().stream()
                .filter(e -> e.getKey().startsWith("BR") && e.getValue() > 0)
                .count();
        assertThat(nonZeroBrNodes).as("All BR nodes must be zero when root BR=0")
                .isEqualTo(0L);
    }

    // ── Provenance import/export ──────────────────────────────────────────────

    @Test
    void importV1JsonStillWorks() throws IOException {
        // Version 1 JSON (legacy format without provenance) must still be importable
        String json = "{\"version\":1,\"requirement\":\"Legacy requirement\","
                + "\"scores\":{\"CO\":35},\"reasons\":{\"CO\":\"Voice comms\"}}";

        SavedAnalysis imported = savedAnalysisService.importFromJson(json);
        assertThat(imported.getVersion()).isEqualTo(1);
        assertThat(imported.getRequirement()).isEqualTo("Legacy requirement");
        assertThat(imported.getScores()).containsEntry("CO", 35);
        assertThat(imported.getSources()).isNull();
        assertThat(imported.getRequirementSourceLinks()).isNull();
    }

    @Test
    void importV2JsonWithProvenance() throws IOException {
        String json = "{\"version\":2,\"requirement\":\"Test requirement\","
                + "\"scores\":{\"CO\":35},\"reasons\":{\"CO\":\"Voice comms\"},"
                + "\"sources\":[{\"sourceType\":\"REGULATION\",\"title\":\"Test Source\"}],"
                + "\"requirementSourceLinks\":[{\"requirementId\":\"REQ-001\","
                + "\"linkType\":\"EXTRACTED_FROM\",\"confidence\":0.91}]}";

        SavedAnalysis imported = savedAnalysisService.importFromJson(json);
        assertThat(imported.getVersion()).isEqualTo(2);
        assertThat(imported.getRequirement()).isEqualTo("Test requirement");
        assertThat(imported.getSources()).hasSize(1);
        assertThat(imported.getSources().get(0).getTitle()).isEqualTo("Test Source");
        assertThat(imported.getRequirementSourceLinks()).hasSize(1);
        assertThat(imported.getRequirementSourceLinks().get(0).getConfidence()).isEqualTo(0.91);
    }

    @Test
    void importRejectsVersionZero() {
        String json = "{\"version\":0,\"requirement\":\"Test\","
                + "\"scores\":{\"CO\":35},\"reasons\":{}}";

        assertThatThrownBy(() -> savedAnalysisService.importFromJson(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported version");
    }

    @Test
    void buildExportWithProvenancePopulatesFields() {
        Map<String, Integer> scores = Map.of("CO", 35);
        Map<String, String> reasons = Map.of("CO", "Voice comms");

        com.taxonomy.dto.SourceArtifactDto src = new com.taxonomy.dto.SourceArtifactDto();
        src.setTitle("Test Source");

        com.taxonomy.dto.RequirementSourceLinkDto link = new com.taxonomy.dto.RequirementSourceLinkDto();
        link.setRequirementId("REQ-001");

        SavedAnalysis saved = savedAnalysisService.buildExport(
                "Test requirement", scores, reasons, "GEMINI",
                List.of(src), List.of(link));

        assertThat(saved.getVersion()).isEqualTo(2);
        assertThat(saved.getSources()).hasSize(1);
        assertThat(saved.getSources().get(0).getTitle()).isEqualTo("Test Source");
        assertThat(saved.getRequirementSourceLinks()).hasSize(1);
        assertThat(saved.getRequirementSourceLinks().get(0).getRequirementId()).isEqualTo("REQ-001");
    }
}
