package com.taxonomy.architecture.controller;

import com.taxonomy.dto.ApqcCoverageResult;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.architecture.service.ArchitectureGapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


/**
 * REST API for Architecture Gap Analysis.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/gap/analyze} — Assess explicit obligations in a selected canonical revision</li>
 *   <li>{@code GET  /api/gap/apqc-coverage} — APQC process coverage analysis</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/gap")
@Tag(name = "Gap Analysis")
public class GapAnalysisApiController {

    private final ArchitectureGapService gapService;

    private final ObligationRequestContext contexts;
    public GapAnalysisApiController(ArchitectureGapService gapService, ObligationRequestContext contexts) {
        this.gapService = gapService; this.contexts = contexts;
    }
    @GetMapping("/profiles")
    public java.util.List<com.taxonomy.dto.ObligationProfile> profiles() {
        return com.taxonomy.architecture.service.ObligationProfiles.all();
    }

    /**
     * Request body for gap analysis.
     */
    public record GapAnalysisRequest(String profileId, String profileVersion, com.taxonomy.dto.EvidenceCompleteness evidenceCompleteness,
            String businessText) {}

    @Operation(summary = "Scoped obligation assessment", description = "Evaluates an explicit profile at an authorized canonical model revision")
    @PostMapping("/analyze")
    public ResponseEntity<GapAnalysisView> analyze(@RequestBody GapAnalysisRequest request,
            @RequestParam String repositoryId, @RequestParam String branch, @RequestParam String commitId,
            jakarta.servlet.http.HttpServletRequest httpRequest) throws java.io.IOException {
        var context = contexts.resolve(repositoryId, branch, commitId, httpRequest);
        if (request.evidenceCompleteness() == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "evidenceCompleteness must be explicit");
        try {
            return ResponseEntity.ok(gapService.analyze(context, commitId, request.profileId(), request.profileVersion(),
                    request.evidenceCompleteness(), request.businessText()));
        } catch (IllegalArgumentException error) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }

    /**
     * Analyses which APQC process categories are covered by the current
     * architecture model.
     */
    @Operation(summary = "APQC coverage analysis",
               description = "Observes explicit APQC elements and accepted links within the selected model revision")
    @GetMapping("/apqc-coverage")
    public ResponseEntity<ApqcCoverageResult> apqcCoverage(@RequestParam String repositoryId, @RequestParam String branch,
            @RequestParam String commitId, jakarta.servlet.http.HttpServletRequest http) throws java.io.IOException {
        var context = contexts.resolve(repositoryId, branch, commitId, http);
        return ResponseEntity.ok(gapService.analyzeApqcCoverage(context, commitId));
    }
}
