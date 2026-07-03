package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.dto.LlmCallDetail;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnalysisSseEventMapper {

    public MappedEvent map(AnalysisStreamEvent event) {
        if (event instanceof AnalysisStreamEvent.Phase phase) {
            return new MappedEvent("phase", Map.of(
                    "message", phase.message(),
                    "progress", phase.progressPercent()));
        }
        if (event instanceof AnalysisStreamEvent.Scores scores) {
            return new MappedEvent("scores", mapScores(scores));
        }
        if (event instanceof AnalysisStreamEvent.Expanding expanding) {
            return new MappedEvent("expanding", Map.of(
                    "parentCode", expanding.parentCode(),
                    "childCodes", expanding.childCodes()));
        }
        if (event instanceof AnalysisStreamEvent.Complete complete) {
            int totalMatched = (int) complete.allScores().values().stream().filter(v -> v > 0).count();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", complete.status());
            payload.put("totalScores", complete.allScores());
            payload.put("totalMatched", totalMatched);
            payload.put("warnings", complete.warnings());
            payload.put("discrepancies", complete.discrepancies());
            return new MappedEvent("complete", payload);
        }
        if (event instanceof AnalysisStreamEvent.Error error) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", error.status());
            payload.put("errorMessage", error.errorMessage());
            payload.put("partialScores", error.partialScores());
            payload.put("warnings", error.warnings());
            payload.put("discrepancies", error.discrepancies());
            return new MappedEvent("error", payload);
        }
        throw new IllegalArgumentException("Unsupported analysis stream event: " + event);
    }

    private Map<String, Object> mapScores(AnalysisStreamEvent.Scores scores) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scores", scores.newScores());
        payload.put("reasons", scores.reasons() != null ? scores.reasons() : Map.of());
        payload.put("description", scores.description());
        payload.put("message", scores.description());
        LlmCallDetail detail = scores.detail();
        if (detail != null) {
            payload.put("prompt", detail.getPrompt() != null ? detail.getPrompt() : "");
            payload.put("rawResponse", detail.getRawResponse() != null ? detail.getRawResponse() : "");
            payload.put("provider", detail.getProvider() != null ? detail.getProvider() : "");
            payload.put("durationMs", detail.getDurationMs());
            if (detail.getError() != null) {
                payload.put("error", detail.getError());
            }
        }
        return payload;
    }

    public record MappedEvent(String name, Object payload) {
    }
}
