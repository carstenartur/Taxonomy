package com.taxonomy.relations.controller;

import com.taxonomy.dto.CoverageStatistics;
import com.taxonomy.relations.model.RequirementCoverage;
import com.taxonomy.relations.service.RequirementCoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for the Requirement Coverage feature.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/coverage/record} — record coverage from an analysis result</li>
 *   <li>{@code GET /api/coverage/node/{code}} — requirements covering a specific node</li>
 *   <li>{@code GET /api/coverage/requirement/{id}} — nodes covered by a requirement</li>
 *   <li>{@code GET /api/coverage/statistics} — overall coverage statistics</li>
 *   <li>{@code GET /api/coverage/density} — requirement density map</li>
 *   <li>{@code DELETE /api/coverage/requirement/{id}} — remove coverage for a requirement</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/coverage")
@Tag(name = "Requirement Coverage")
public class CoverageApiController {

    private final RequirementCoverageService coverageService;

    public CoverageApiController(RequirementCoverageService coverageService) {
        this.coverageService = coverageService;
    }

    /**
     * Request body for recording coverage from an analysis result.
     *
     * @param requirementId   identifier for the requirement (e.g. "REQ-101")
     * @param requirementText original business text
     * @param scores          map of nodeCode → score (0–100)
     * @param minScore        minimum score threshold (inclusive); 0 means use the default (50)
     */
    public record RecordCoverageRequest(
            String requirementId,
            String requirementText,
            Map<String, Integer> scores,
            int minScore) {
    }

    /**
     * Records coverage from an analysis result. Scores below {@code minScore} are discarded.
     */
    @Operation(summary = "Record coverage",
               description = "Stores nodeCode→score mappings above the threshold for a requirement")
    @PostMapping("/record")
    public ResponseEntity<Void> recordCoverage(@RequestBody RecordCoverageRequest request) {
        coverageService.analyzeCoverage(
                request.scores() != null ? request.scores() : Map.of(),
                request.requirementId(),
                request.requirementText(),
                request.minScore());
        return ResponseEntity.ok().build();
    }

    /**
     * Returns all requirements that cover a given taxonomy node code.
     */
    @Operation(summary = "Coverage for node",
               description = "Returns all requirements covering a specific node code")
    @GetMapping("/node/{code}")
    public ResponseEntity<List<RequirementCoverage>> getCoverageForNode(
            @Parameter(description = "Taxonomy node code") @PathVariable String code) {
        return ResponseEntity.ok(coverageService.getCoverageForNode(code));
    }

    /**
     * Returns all nodes covered by a specific requirement.
     */
    @Operation(summary = "Coverage for requirement",
               description = "Returns all nodes covered by a specific requirement ID")
    @GetMapping("/requirement/{id}")
    public ResponseEntity<List<RequirementCoverage>> getCoverageForRequirement(
            @Parameter(description = "Requirement identifier") @PathVariable String id) {
        return ResponseEntity.ok(coverageService.getCoverageForRequirement(id));
    }

    /**
     * Returns overall coverage statistics for the taxonomy.
     */
    @Operation(summary = "Coverage statistics",
               description = "Returns overall coverage statistics: totals, percentages, top-covered, and gap candidates")
    @GetMapping("/statistics")
    public ResponseEntity<CoverageStatistics> getCoverageStatistics() {
        return ResponseEntity.ok(coverageService.getCoverageStatistics());
    }

    /**
     * Returns the requirement density map (nodeCode → requirementCount).
     */
    @Operation(summary = "Density map",
               description = "Returns a map of nodeCode → requirementCount for heatmap visualisation")
    @GetMapping("/density")
    public ResponseEntity<Map<String, Integer>> getDensityMap() {
        return ResponseEntity.ok(coverageService.getRequirementDensityMap());
    }

    /**
     * Removes all coverage records for the given requirement ID.
     */
    @Operation(summary = "Delete coverage",
               description = "Removes all coverage records for a given requirement ID")
    @DeleteMapping("/requirement/{id}")
    public ResponseEntity<Void> deleteCoverage(
            @Parameter(description = "Requirement identifier") @PathVariable String id) {
        coverageService.deleteCoverageForRequirement(id);
        return ResponseEntity.noContent().build();
    }
}
