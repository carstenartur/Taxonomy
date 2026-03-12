package com.taxonomy.controller;

import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.service.ArchitectureGapService;
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
}
