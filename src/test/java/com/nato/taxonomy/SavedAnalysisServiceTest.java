package com.nato.taxonomy;

import com.nato.taxonomy.dto.SavedAnalysis;
import com.nato.taxonomy.service.SavedAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
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

        assertThat(saved.getVersion()).isEqualTo(1);
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
}
