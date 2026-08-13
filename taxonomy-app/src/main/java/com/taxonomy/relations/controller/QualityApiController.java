package com.taxonomy.relations.controller;

import com.taxonomy.dto.ProvenanceMetrics;
import com.taxonomy.dto.RelationQualityMetrics;
import com.taxonomy.dto.RelationTypeMetrics;
import com.taxonomy.dto.TopRejectedProposal;
import com.taxonomy.relations.service.RelationQualityService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for the Relation Quality Dashboard.
 *
 * <p>Every request resolves one stable selected repository context. Responses
 * are not cacheable because the URL is intentionally independent of the
 * repository/workspace selection stored in the user session.</p>
 */
@RestController
@RequestMapping("/api/relations/metrics")
@Tag(name = "Quality Metrics")
public class QualityApiController {

    private final RelationQualityService qualityService;
    private final WorkspaceResolver workspaceResolver;

    public QualityApiController(
            RelationQualityService qualityService,
            WorkspaceResolver workspaceResolver) {
        this.qualityService = qualityService;
        this.workspaceResolver = workspaceResolver;
    }

    /** Returns the full quality dashboard metrics. */
    @Operation(
            summary = "Quality dashboard",
            description = "Returns quality metrics for the selected repository context")
    @GetMapping
    public ResponseEntity<RelationQualityMetrics> getMetrics() {
        RepositoryContext context = currentContext();
        return noStore(qualityService.calculateMetrics(context));
    }

    /** Returns metrics broken down by relation type. */
    @Operation(
            summary = "Metrics by relation type",
            description = "Returns relation-type metrics for the selected repository context")
    @GetMapping("/by-type")
    public ResponseEntity<List<RelationTypeMetrics>> getMetricsByType() {
        RepositoryContext context = currentContext();
        return noStore(qualityService.metricsByRelationType(context));
    }

    /** Returns metrics broken down by provenance. */
    @Operation(
            summary = "Metrics by provenance",
            description = "Returns provenance metrics for the selected repository context")
    @GetMapping("/by-provenance")
    public ResponseEntity<List<ProvenanceMetrics>> getMetricsByProvenance() {
        RepositoryContext context = currentContext();
        return noStore(qualityService.metricsByProvenance(context));
    }

    /** Returns the highest-confidence rejected proposals in the visible scope. */
    @Operation(
            summary = "Top rejected proposals",
            description = "Returns rejected proposals from the selected repository context")
    @GetMapping("/top-rejected")
    public ResponseEntity<List<TopRejectedProposal>> getTopRejected(
            @Parameter(description = "Maximum number of results")
            @RequestParam(defaultValue = "10") int limit) {
        RepositoryContext context = currentContext();
        return noStore(qualityService.topRejected(limit, context));
    }

    private RepositoryContext currentContext() {
        return workspaceResolver.resolveCurrentRepositoryContext();
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
