package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisSseEventMapperTest {

    private final AnalysisSseEventMapper mapper = new AnalysisSseEventMapper();

    @Test
    void mapScoresPreservesExistingPayloadFields() {
        LlmCallDetail detail = new LlmCallDetail();
        detail.setPrompt("prompt");
        detail.setRawResponse("raw");
        detail.setProvider("GEMINI");
        detail.setDurationMs(42L);
        detail.setError("minor");

        AnalysisSseEventMapper.MappedEvent mapped = mapper.map(new AnalysisStreamEvent.Scores(
                Map.of("CP", 80),
                Map.of("CP", "reason"),
                "Capabilities scored 80/100",
                detail));

        assertThat(mapped.name()).isEqualTo("scores");
        assertThat(mapped.payload()).isEqualTo(Map.ofEntries(
                Map.entry("scores", Map.of("CP", 80)),
                Map.entry("reasons", Map.of("CP", "reason")),
                Map.entry("description", "Capabilities scored 80/100"),
                Map.entry("message", "Capabilities scored 80/100"),
                Map.entry("prompt", "prompt"),
                Map.entry("rawResponse", "raw"),
                Map.entry("provider", "GEMINI"),
                Map.entry("durationMs", 42L),
                Map.entry("error", "minor")));
    }

    @Test
    void mapTerminalEventsPreserveExistingNamesAndPayloads() {
        AnalysisSseEventMapper.MappedEvent complete = mapper.map(new AnalysisStreamEvent.Complete(
                "SUCCESS",
                Map.of("CP", 80, "CR", 0),
                List.of("warn"),
                List.of()));
        AnalysisSseEventMapper.MappedEvent error = mapper.map(new AnalysisStreamEvent.Error(
                "PARTIAL",
                "boom",
                Map.of("CP", 80),
                List.of("warn"),
                List.of()));

        assertThat(complete.name()).isEqualTo("complete");
        assertThat(complete.payload()).isEqualTo(Map.ofEntries(
                Map.entry("status", "SUCCESS"),
                Map.entry("totalScores", Map.of("CP", 80, "CR", 0)),
                Map.entry("totalMatched", 1),
                Map.entry("warnings", List.of("warn")),
                Map.entry("discrepancies", List.of())));
        assertThat(error.name()).isEqualTo("error");
        assertThat(error.payload()).isEqualTo(Map.ofEntries(
                Map.entry("status", "PARTIAL"),
                Map.entry("errorMessage", "boom"),
                Map.entry("partialScores", Map.of("CP", 80)),
                Map.entry("warnings", List.of("warn")),
                Map.entry("discrepancies", List.of())));
    }
}
