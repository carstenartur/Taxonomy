package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.ProvenanceMetrics;
import com.nato.taxonomy.dto.RelationQualityMetrics;
import com.nato.taxonomy.dto.RelationTypeMetrics;
import com.nato.taxonomy.dto.TopRejectedProposal;
import com.nato.taxonomy.service.RelationQualityService;
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
public class QualityApiController {

    private final RelationQualityService qualityService;

    public QualityApiController(RelationQualityService qualityService) {
        this.qualityService = qualityService;
    }

    /**
     * Returns the full quality dashboard metrics.
     */
    @GetMapping
    public ResponseEntity<RelationQualityMetrics> getMetrics() {
        return ResponseEntity.ok(qualityService.calculateMetrics());
    }

    /**
     * Returns metrics broken down by relation type.
     */
    @GetMapping("/by-type")
    public ResponseEntity<List<RelationTypeMetrics>> getMetricsByType() {
        return ResponseEntity.ok(qualityService.metricsByRelationType());
    }

    /**
     * Returns metrics broken down by provenance.
     */
    @GetMapping("/by-provenance")
    public ResponseEntity<List<ProvenanceMetrics>> getMetricsByProvenance() {
        return ResponseEntity.ok(qualityService.metricsByProvenance());
    }

    /**
     * Returns top rejected proposals ordered by confidence descending.
     */
    @GetMapping("/top-rejected")
    public ResponseEntity<List<TopRejectedProposal>> getTopRejected(
            @RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(qualityService.topRejected(limit));
    }
}
