package com.taxonomy.architecture.controller;

import com.taxonomy.dto.ApqcCoverageResult;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.architecture.service.ArchitectureGapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for Architecture Gap Analysis.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/gap/analyze} — Perform gap analysis on scored nodes</li>
 *   <li>{@code GET  /api/gap/apqc-coverage} — APQC process coverage analysis</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gap")
@Tag(name = "Gap Analysis")
public class GapAnalysisApiController {

    private final ArchitectureGapService gapService;

    public GapAnalysisApiController(ArchitectureGapService gapService) {
        this.gapService = gapService;
    }

    /**
     * Request body for gap analysis.
     */
    public record GapAnalysisRequest(
            Map<String, Integer> scores,
            String businessText,
            int minScore) {
    }

    /**
     * Performs an architecture gap analysis based on requirement scores.
     */
    @Operation(summary = "Architecture gap analysis",
               description = "Identifies missing relations and incomplete patterns for scored nodes")
    @PostMapping("/analyze")
    public ResponseEntity<GapAnalysisView> analyze(@RequestBody GapAnalysisRequest request) {
        GapAnalysisView view = gapService.analyze(
                request.scores() != null ? request.scores() : Map.of(),
                request.businessText(),
                request.minScore());
        return ResponseEntity.ok(view);
    }

    /**
     * Analyses which APQC process categories are covered by the current
     * architecture model.
     */
    @Operation(summary = "APQC coverage analysis",
               description = "Reports which APQC process categories are covered and which have gaps")
    @GetMapping("/apqc-coverage")
    public ResponseEntity<ApqcCoverageResult> apqcCoverage(
            @RequestParam(required = false) String businessText) {
        ApqcCoverageResult result = gapService.analyzeApqcCoverage(businessText);
        return ResponseEntity.ok(result);
    }
}
