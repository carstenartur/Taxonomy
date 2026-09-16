package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.analysis.controller.AnalysisSseEventMapper;
import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;
import java.util.Map;

/** Regression checks for evidence returned just before a cooperative stop. */
class AnalysisStoppedEvidenceTest {
    @Test void postResponseStopKeepsLiveScoresAndDiagnosticText() {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var context = WorkspaceContext.SHARED;
        try (var handle = registry.open(null, "owner", context, null)) {
            var detail = detail();
            try {
                AnalysisRunControl.call("MOCK", "CP", () -> {
                    registry.cancel(handle.id(), "owner", context);
                    return detail;
                });
                throw new AssertionError("Cancellation must be observed after the response");
            } catch (AnalysisStoppedException stopped) {
                require(stopped.partialScores().equals(detail.getScores()), "Final scores lost");
            }
            var snapshot = registry.snapshot(handle.id(), "owner", context);
            require(snapshot.rawScores().equals(detail.getScores()), "Live scores lost");
            require("STOPPING".equals(snapshot.phase()), "Stop misreported as normal scoring");
            require("STOPPED".equals(snapshot.calls().getFirst().status()), "Call not stopped");
            var diagnostic = registry.callDetail(handle.id(), snapshot.calls().getFirst().id(), "owner", context);
            require("prompt".equals(diagnostic.prompt()), "Last prompt lost");
            require("response".equals(diagnostic.response()), "Last response lost");
            handle.finish("PARTIAL");
            require("CANCELLED".equals(registry.snapshot(handle.id(), "owner", context).status()), "Stop reason lost");
        }
    }

    @Test void fullDiagnosticBoundaryIsMarkedWhenMoreTextArrives() {
        var accumulator = new LlmDetailAccumulator();
        var first = new LlmCallDetail();
        first.setError("x".repeat(32_768));
        accumulator.add(first);
        require(accumulator.result().getError().length() == 32_768, "Exact boundary not reached");
        var next = detail();
        next.setError("later diagnostic");
        accumulator.add(next);
        var result = accumulator.result();
        require(result.getError().length() <= 32_768, "Diagnostic limit exceeded");
        require(result.getError().endsWith("[diagnostic transcript truncated]"), "Truncation was silent");
        require(result.getScores().equals(next.getScores()), "Scores were truncated with diagnostics");
    }

    @Test void partialDiscrepanciesAreRetainedWithoutDuplicates() throws Exception {
        var detail = detail();
        var discrepancy = new TaxonomyDiscrepancy("CP", 50, 80);
        detail.setDiscrepancy(discrepancy);
        var stopped = new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
        stopped.withPartial(detail).withPartial(detail);
        var retained = stopped.partialDiscrepancies();
        require(List.of(discrepancy).equals(retained), "Discrepancy lost or duplicated");
        require(stopped.partialReasons().equals(detail.getReasons()), "Reasons lost");
    }

    @Test void streamingErrorPayloadCarriesPartialReasonsAndDiscrepancies() {
        var discrepancy = new TaxonomyDiscrepancy("CP", 50, 80);
        var event = new AnalysisStreamEvent.Error("PARTIAL", "CANCELLED: stopped",
                Map.of("CP", 80), List.of(), List.of(discrepancy), List.of(),
                Map.of("CP", "Retained reason"));
        var mapped = new AnalysisSseEventMapper().map(event);
        var payload = (Map<?, ?>) mapped.payload();
        require("error".equals(mapped.name()), "Wrong terminal event type");
        require(event.partialReasons().equals(payload.get("reasons")), "Terminal reasons lost");
        require(event.partialScores().equals(payload.get("rawScores")), "Terminal scores lost");
        require(event.discrepancies().equals(payload.get("discrepancies")), "Terminal discrepancy lost");
    }

    @Test void legacyErrorConstructorRetainsAnEmptyReasonMap() {
        var event = new AnalysisStreamEvent.Error("PARTIAL", "failed", Map.of(), List.of(), List.of(), List.of());
        var payload = (Map<?, ?>) new AnalysisSseEventMapper().map(event).payload();
        require(Map.of().equals(payload.get("reasons")), "Legacy error contract broke");
    }

    private static LlmCallDetail detail() {
        var detail = new LlmCallDetail();
        detail.setScores(Map.of("CP", 80));
        detail.setReasons(Map.of("CP", "Retained reason"));
        detail.setPrompt("prompt");
        detail.setRawResponse("response");
        return detail;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
