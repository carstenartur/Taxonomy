package com.taxonomy.architecture.controller;

import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.architecture.service.ArchitecturePatternService;
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
    private final ObligationRequestContext contexts;

    public PatternDetectionApiController(ArchitecturePatternService patternService, ObligationRequestContext contexts) {
        this.patternService = patternService; this.contexts = contexts;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public java.util.Map<String,String> invalidPolicy(IllegalArgumentException error) { return java.util.Map.of("message", error.getMessage()); }

    /**
     * Request body for pattern detection on scored nodes.
     */
    public record PatternDetectionRequest(
            Map<String, Double> scores,
            int minScore) {
    }

    /**
     * Detects architecture patterns starting from a specific node.
     */
    @Operation(summary = "Detect patterns for node",
               description = "Checks which standard architecture patterns are present starting from the given node")
    @GetMapping("/detect")
    public ResponseEntity<PatternDetectionView> detectForNode(
            @Parameter(description = "Taxonomy node code") @RequestParam String nodeCode,
            @RequestParam String repositoryId, @RequestParam String branch, @RequestParam String commitId,
            jakarta.servlet.http.HttpServletRequest http) {
        PatternDetectionView view = patternService.detectForNode(contexts.resolve(repositoryId, branch, commitId, http), commitId, nodeCode);
        return ResponseEntity.ok(view);
    }

    /**
     * Detects architecture patterns across all scored nodes.
     */
    @Operation(summary = "Detect patterns for scores",
               description = "Detects architecture patterns across all nodes above the score threshold")
    @PostMapping("/detect")
    public ResponseEntity<PatternDetectionView> detectForScores(@RequestBody PatternDetectionRequest request,
            @RequestParam String repositoryId, @RequestParam String branch, @RequestParam String commitId,
            jakarta.servlet.http.HttpServletRequest http) {
        PatternDetectionView view = patternService.detectForScores(contexts.resolve(repositoryId, branch, commitId, http), commitId,
                request.scores() != null ? request.scores() : Map.of(),
                request.minScore());
        return ResponseEntity.ok(view);
    }
}
