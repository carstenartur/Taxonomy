package com.taxonomy.relations.controller;

import com.taxonomy.dto.ProvenanceMetrics;
import com.taxonomy.dto.RelationQualityMetrics;
import com.taxonomy.dto.RelationTypeMetrics;
import com.taxonomy.dto.TopRejectedProposal;
import com.taxonomy.relations.service.RelationQualityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for the Relation Quality Dashboard.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/relations/metrics} — full quality dashboard</li>
 *   <li>{@code GET /api/relations/metrics/by-type} — metrics by relation type</li>
 *   <li>{@code GET /api/relations/metrics/by-provenance} — metrics by provenance</li>
 *   <li>{@code GET /api/relations/metrics/top-rejected} — top rejected proposals</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/relations/metrics")
@Tag(name = "Quality Metrics")
public class QualityApiController {

    private final RelationQualityService qualityService;

    public QualityApiController(RelationQualityService qualityService) {
        this.qualityService = qualityService;
    }

    /**
     * Returns the full quality dashboard metrics.
     */
    @Operation(summary = "Quality dashboard", description = "Returns the full quality dashboard metrics")
    @GetMapping
    public ResponseEntity<RelationQualityMetrics> getMetrics() {
        return ResponseEntity.ok(qualityService.calculateMetrics());
    }

    /**
     * Returns metrics broken down by relation type.
     */
    @Operation(summary = "Metrics by relation type", description = "Returns quality metrics broken down by relation type")
    @GetMapping("/by-type")
    public ResponseEntity<List<RelationTypeMetrics>> getMetricsByType() {
        return ResponseEntity.ok(qualityService.metricsByRelationType());
    }

    /**
     * Returns metrics broken down by provenance.
     */
    @Operation(summary = "Metrics by provenance", description = "Returns quality metrics broken down by provenance")
    @GetMapping("/by-provenance")
    public ResponseEntity<List<ProvenanceMetrics>> getMetricsByProvenance() {
        return ResponseEntity.ok(qualityService.metricsByProvenance());
    }

    /**
     * Returns top rejected proposals ordered by confidence descending.
     */
    @Operation(summary = "Top rejected proposals", description = "Returns top rejected proposals ordered by confidence descending")
    @GetMapping("/top-rejected")
    public ResponseEntity<List<TopRejectedProposal>> getTopRejected(
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(qualityService.topRejected(limit));
    }
}
