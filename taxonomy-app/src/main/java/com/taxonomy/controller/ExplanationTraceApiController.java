package com.taxonomy.controller;

import com.taxonomy.dto.ExplanationTrace;
import com.taxonomy.service.ExplanationTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for Structured Explainable Reasoning.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/explain/{nodeCode}} — Get structured explanation for a node</li>
 *   <li>{@code POST /api/explain}            — Get explanations for all scored nodes</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/explain")
@Tag(name = "Explainable Reasoning")
public class ExplanationTraceApiController {

    private final ExplanationTraceService traceService;

    public ExplanationTraceApiController(ExplanationTraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * Request body for explanation trace.
     */
    public record ExplainRequest(
            Map<String, Integer> scores,
            Map<String, String> reasons,
            String businessText,
            int minScore) {
    }

    @Operation(summary = "Explain single node",
               description = "Returns a structured explanation trace for why a specific node was scored")
    @PostMapping("/{nodeCode}")
    public ResponseEntity<ExplanationTrace> explainNode(
            @Parameter(description = "Taxonomy node code") @PathVariable String nodeCode,
            @RequestBody ExplainRequest request) {
        ExplanationTrace trace = traceService.buildTrace(
                nodeCode,
                request.scores() != null ? request.scores() : Map.of(),
                request.reasons(),
                request.businessText());
        return ResponseEntity.ok(trace);
    }

    @Operation(summary = "Explain all scored nodes",
               description = "Returns structured explanation traces for all nodes above the score threshold")
    @PostMapping
    public ResponseEntity<Map<String, ExplanationTrace>> explainAll(
            @RequestBody ExplainRequest request) {
        Map<String, ExplanationTrace> traces = traceService.buildTraces(
                request.scores() != null ? request.scores() : Map.of(),
                request.reasons(),
                request.businessText(),
                request.minScore());
        return ResponseEntity.ok(traces);
    }
}
