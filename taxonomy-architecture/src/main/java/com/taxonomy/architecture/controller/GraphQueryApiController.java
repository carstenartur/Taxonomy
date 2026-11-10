package com.taxonomy.architecture.controller;

import com.taxonomy.dto.ApqcHierarchyView;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import com.taxonomy.dto.ChangeImpactView;
import com.taxonomy.dto.EnrichedChangeImpactView;
import com.taxonomy.dto.GraphNeighborhoodView;
import com.taxonomy.dto.RequirementImpactView;
import com.taxonomy.architecture.service.ApqcHierarchyService;
import com.taxonomy.architecture.service.ArchitectureGraphQueryService;
import com.taxonomy.architecture.service.EnrichedImpactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST API for graph-based architecture queries.
 *
 * <p>Every endpoint requires explicit repositoryId, branch and full commitId query
 * parameters plus workspaceId (blank for central) or X-Taxonomy-Workspace-Id.
 * The existing workspace resolver owns authorization. Unproved or moved snapshots
 * fail closed; an empty answer applies only to its declared scope and policy.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/graph/impact} — Requirement impact analysis</li>
 *   <li>{@code GET  /api/graph/node/{code}/upstream} — Upstream neighborhood</li>
 *   <li>{@code GET  /api/graph/node/{code}/downstream} — Downstream neighborhood</li>
 *   <li>{@code GET  /api/graph/node/{code}/failure-impact} — Failure/change impact</li>
 *   <li>{@code GET  /api/graph/node/{code}/enriched-failure-impact} — Enriched failure impact with requirement correlation</li>
 *   <li>{@code GET  /api/graph/apqc-hierarchy} — APQC process hierarchy from imported DSL</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph Queries", description = "Explicit authorized repository/workspace/branch/commit graph snapshots")
public class GraphQueryApiController {

    private final WorkspaceResolver workspaceResolver;
    private final ArchitectureGraphQueryService graphQueryService;
    private final EnrichedImpactService enrichedImpactService;
    private final ApqcHierarchyService apqcHierarchyService;

    public GraphQueryApiController(ArchitectureGraphQueryService graphQueryService,
                                   EnrichedImpactService enrichedImpactService,
                                   ApqcHierarchyService apqcHierarchyService, WorkspaceResolver workspaceResolver) {
        this.workspaceResolver = workspaceResolver;
        this.graphQueryService = graphQueryService;
        this.enrichedImpactService = enrichedImpactService;
        this.apqcHierarchyService = apqcHierarchyService;
    }

    /**
     * Requirement impact: which elements are affected by a business requirement?
     *
     * <p>Request body must contain {@code scores} (map of nodeCode → score 0–100),
     * {@code businessText}, and optionally {@code maxHops} (default 2).
     */
    @Operation(summary = "Requirement impact analysis", description = "Determines which elements are affected by a business requirement")
    @PostMapping("/impact")
    public ResponseEntity<RequirementImpactView> findImpact(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> rawScores = (Map<String, Object>) body.get("scores");
        String businessText = (String) body.get("businessText");
        int maxHops = body.containsKey("maxHops")
                ? ((Number) body.get("maxHops")).intValue()
                : 2;

        if (businessText == null || businessText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, Double> scores = new java.util.LinkedHashMap<>();
        if (rawScores != null) {
            for (Map.Entry<String, Object> entry : rawScores.entrySet()) {
                scores.put(entry.getKey(), com.taxonomy.dto.AnalysisPercentages.require((Number) entry.getValue()));
            }
        }

        RequirementImpactView view = graphQueryService.findImpactForRequirement(
                selected(request), commit(request), scores, businessText, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Upstream neighborhood: what feeds into this element?
     */
    @Operation(summary = "Upstream neighbourhood", description = "Returns nodes that feed into this element")
    @GetMapping("/node/{code}/upstream")
    public ResponseEntity<GraphNeighborhoodView> findUpstream(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "2") int maxHops, HttpServletRequest request) {
        GraphNeighborhoodView view = graphQueryService.findUpstream(selected(request), commit(request), code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Downstream neighborhood: what depends on this element?
     */
    @Operation(summary = "Downstream neighbourhood", description = "Returns nodes that depend on this element")
    @GetMapping("/node/{code}/downstream")
    public ResponseEntity<GraphNeighborhoodView> findDownstream(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "2") int maxHops, HttpServletRequest request) {
        GraphNeighborhoodView view = graphQueryService.findDownstream(selected(request), commit(request), code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Failure/change impact: what is affected if this element fails or changes?
     */
    @Operation(summary = "Failure/change impact", description = "Returns what is affected if this element fails or changes")
    @GetMapping("/node/{code}/failure-impact")
    public ResponseEntity<ChangeImpactView> findFailureImpact(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "3") int maxHops, HttpServletRequest request) {
        ChangeImpactView view = graphQueryService.findFailureImpact(selected(request), commit(request), code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Enriched failure/change impact: includes requirement correlation for each affected element.
     */
    @Operation(summary = "Enriched failure impact",
               description = "Returns failure impact enriched with requirement coverage data and risk score")
    @GetMapping("/node/{code}/enriched-failure-impact")
    public ResponseEntity<EnrichedChangeImpactView> findEnrichedFailureImpact(
            @Parameter(description = "Taxonomy node code") @PathVariable String code,
            @Parameter(description = "Maximum traversal hops") @RequestParam(defaultValue = "3") int maxHops, HttpServletRequest request) {
        EnrichedChangeImpactView view = enrichedImpactService.findEnrichedFailureImpact(selected(request), commit(request), code, maxHops);
        return ResponseEntity.ok(view);
    }

    /**
     * Returns the APQC process hierarchy extracted from imported DSL documents.
     *
     * <p>Reads the selected canonical DSL commit for elements with the
     * {@code x-source-framework: "apqc"} extension and reconstructs the
     * parent–child hierarchy using {@code x-apqc-parent} chains.
     */
    @Operation(summary = "APQC hierarchy",
               description = "Returns APQC process elements from imported DSL documents as a hierarchy tree")
    @GetMapping("/apqc-hierarchy")
    public ResponseEntity<ApqcHierarchyView> apqcHierarchy(HttpServletRequest request) {
        return ResponseEntity.ok(apqcHierarchyService.buildHierarchy(selected(request), commit(request)));
    }
    private RepositoryContext selected(HttpServletRequest request) {
        String workspace = WorkspaceContextResolver.requestedWorkspaceId(request);
        String repository = request.getParameter("repositoryId");
        String branch = request.getParameter("branch");
        if (workspace == null || repository == null || repository.isBlank() || branch == null || branch.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Explicit repositoryId, workspaceId and branch are required");
        commit(request);
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContextForRead();
        if (!repository.equals(context.repositoryId()) || !branch.equals(context.branch())
                || !workspace.equals(Objects.toString(context.workspaceId(), "")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Selected graph context changed");
        return context;
    }

    private String commit(HttpServletRequest request) {
        try { return WorkspaceDslReadPort.normalizeCommitId(request.getParameter("commitId")); }
        catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Explicit full commitId is required", error);
        }
    }

    @ExceptionHandler({com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException.class,
            IllegalStateException.class})
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "UNAVAILABLE", "message", error.getMessage()));
    }
}
