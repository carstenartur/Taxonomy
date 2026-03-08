package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.PatternDetectionView;
import com.nato.taxonomy.service.ArchitecturePatternService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for Architecture Pattern Detection.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/patterns/detect?nodeCode=...} — Detect patterns for a single node</li>
 *   <li>{@code POST /api/patterns/detect} — Detect patterns across scored nodes</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/patterns")
@Tag(name = "Pattern Detection")
public class PatternDetectionApiController {

    private final ArchitecturePatternService patternService;

    public PatternDetectionApiController(ArchitecturePatternService patternService) {
        this.patternService = patternService;
    }

    /**
     * Request body for pattern detection on scored nodes.
     */
    public record PatternDetectionRequest(
            Map<String, Integer> scores,
            int minScore) {
    }

    /**
     * Detects architecture patterns starting from a specific node.
     */
    @Operation(summary = "Detect patterns for node",
               description = "Checks which standard architecture patterns are present starting from the given node")
    @GetMapping("/detect")
    public ResponseEntity<PatternDetectionView> detectForNode(
            @Parameter(description = "Taxonomy node code") @RequestParam String nodeCode) {
        PatternDetectionView view = patternService.detectForNode(nodeCode);
        return ResponseEntity.ok(view);
    }

    /**
     * Detects architecture patterns across all scored nodes.
     */
    @Operation(summary = "Detect patterns for scores",
               description = "Detects architecture patterns across all nodes above the score threshold")
    @PostMapping("/detect")
    public ResponseEntity<PatternDetectionView> detectForScores(@RequestBody PatternDetectionRequest request) {
        PatternDetectionView view = patternService.detectForScores(
                request.scores() != null ? request.scores() : Map.of(),
                request.minScore());
        return ResponseEntity.ok(view);
    }
}
