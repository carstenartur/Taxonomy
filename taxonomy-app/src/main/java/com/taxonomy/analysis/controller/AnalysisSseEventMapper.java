package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisScoreSemantics;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyNodeDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnalysisSseEventMapper {

    private final TaxonomyService taxonomyService;

    /** Retained for focused mapper tests that do not need catalogue semantics. */
    public AnalysisSseEventMapper() {
        this(null);
    }

    @Autowired
    public AnalysisSseEventMapper(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

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
            AnalysisScoreSemantics.Derived semantics = derive(complete.allScores());
            int totalMatched = (int) semantics.effectiveScores().values().stream()
                    .filter(value -> value > 0)
                    .count();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", complete.status());
            payload.put("totalScores", semantics.effectiveScores());
            payload.put("rawScores", complete.allScores());
            payload.put("effectiveScores", semantics.effectiveScores());
            payload.put("productSuitabilityScores", semantics.productSuitabilityScores());
            payload.put("scoreDetails", semantics.scoreDetails());
            payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);
            payload.put("scoreSemanticsWarnings", semantics.warnings());
            payload.put("totalMatched", totalMatched);
            payload.put("warnings", complete.warnings());
            payload.put("discrepancies", complete.discrepancies());
            payload.put("productCoverageGaps", complete.productCoverageGaps());
            return new MappedEvent("complete", payload);
        }
        if (event instanceof AnalysisStreamEvent.Error error) {
            AnalysisScoreSemantics.Derived semantics = derive(error.partialScores());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", error.status());
            payload.put("errorMessage", error.errorMessage());
            payload.put("partialScores", semantics.effectiveScores());
            payload.put("rawScores", error.partialScores());
            payload.put("effectiveScores", semantics.effectiveScores());
            payload.put("productSuitabilityScores", semantics.productSuitabilityScores());
            payload.put("scoreDetails", semantics.scoreDetails());
            payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);
            payload.put("scoreSemanticsWarnings", semantics.warnings());
            payload.put("warnings", error.warnings());
            payload.put("discrepancies", error.discrepancies());
            payload.put("productCoverageGaps", error.productCoverageGaps());
            return new MappedEvent("error", payload);
        }
        throw new IllegalArgumentException("Unsupported analysis stream event: " + event);
    }

    private Map<String, Object> mapScores(AnalysisStreamEvent.Scores scores) {
        AnalysisScoreSemantics.Derived semantics = derive(scores.newScores());
        Map<String, Object> payload = new LinkedHashMap<>();
        // Keep the incremental compatibility field raw. The browser has the previously emitted
        // parent value and can therefore derive an exact product effective score immediately.
        payload.put("scores", scores.newScores());
        payload.put("rawScores", scores.newScores());
        payload.put("effectiveScores", semantics.effectiveScores());
        payload.put("scoreDetails", semantics.scoreDetails());
        payload.put("scoreSemanticsVersion", AnalysisScoreSemantics.CURRENT_VERSION);
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

    private AnalysisScoreSemantics.Derived derive(Map<String, Integer> rawScores) {
        List<TaxonomyNodeDto> tree = taxonomyService == null
                ? List.of() : taxonomyService.getFullTree();
        return AnalysisScoreSemantics.derive(rawScores, tree);
    }

    public record MappedEvent(String name, Object payload) {
    }
}
