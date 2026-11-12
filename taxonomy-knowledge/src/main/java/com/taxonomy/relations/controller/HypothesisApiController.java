package com.taxonomy.relations.controller;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.relations.service.HypothesisSourceReadService;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Relation-owned HTTP operations retaining the established hypothesis URLs. */
@RestController
@RequestMapping("/api/dsl/hypotheses")
@Tag(name = "Architecture DSL")
public class HypothesisApiController {

    private final HypothesisService hypothesisService;
    private final WorkspaceResolver workspaceResolver;
    private final HypothesisSourceReadService sourceReads;

    public HypothesisApiController(HypothesisService hypothesisService, WorkspaceResolver workspaceResolver,
                                   HypothesisSourceReadService sourceReads) {
        this.sourceReads = sourceReads;
        this.hypothesisService = hypothesisService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    @Operation(summary = "List hypotheses at the selected Git revision, optionally filtered by status",
            description = "The default view reads one selected branch head. An optional full commitId must match it. "
                    + "X-Taxonomy-Commit identifies the source and ETag identifies the filtered representation. "
                    + "Use view=working for retained analysis and materialization metadata without a Git precondition. "
                    + "When central repositories overlap, select a repositoryId and branch with an empty workspace pin.",
            parameters = {
                    @Parameter(name = "repositoryId", in = ParameterIn.QUERY, description = "Selected repository"),
                    @Parameter(name = "branch", in = ParameterIn.QUERY, description = "Selected branch"),
                    @Parameter(name = "workspaceId", in = ParameterIn.QUERY, description = "Workspace pin; empty selects central")
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Committed hypothesis facts or explicit working rows",
                            content = @Content(array = @ArraySchema(schema = @Schema(oneOf = {
                                    HypothesisSourceReadService.Item.class, RelationHypothesis.class})))),
                    @ApiResponse(responseCode = "304", description = "The selected representation matches If-None-Match"),
                    @ApiResponse(responseCode = "400", description = "Invalid view or commit precondition"),
                    @ApiResponse(responseCode = "404", description = "Selected branch or repository is unavailable"),
                    @ApiResponse(responseCode = "409", description = "Selection, commit or retained binding needs reconciliation"),
                    @ApiResponse(responseCode = "503", description = "The selected source could not be verified")
            })
    public ResponseEntity<List<?>> listHypotheses(
            @RequestParam(required = false) HypothesisStatus status,
            @RequestParam(required = false) String commitId,
            @RequestParam(defaultValue = "current") String view) {
        RepositoryContext context = readRepositoryContext();
        if ("working".equals(view) && commitId == null) {
            var rows = status == null ? hypothesisService.findAll(context) : hypothesisService.findByStatus(status, context);
            return ResponseEntity.ok().header(RelationApiController.READ_MODEL_HEADER, "WORKING")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(rows);
        }
        if (!"current".equals(view)) return ResponseEntity.badRequest().build();
        String expected;
        try {
            expected = commitId == null ? null : WorkspaceDslReadPort.normalizeCommitId(commitId);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().build();
        }
        var result = sourceReads.read(context, expected, status);
        var response = ResponseEntity.status(switch (result.state()) {
            case READY -> HttpStatus.OK;
            case STALE -> HttpStatus.CONFLICT;
            case MISSING -> result.commit() == null ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            case FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        }).header(RelationApiController.PROJECTION_STATE_HEADER, result.state().name())
                .header(RelationApiController.READ_MODEL_HEADER, "GIT")
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (result.commit() != null) response.header("X-Taxonomy-Commit", result.commit());
        if (result.problem() != null) {
            return response.header("X-Taxonomy-Read-Problem", result.problem().name()).build();
        }
        return response.eTag(result.entityTag()).body(result.rows());
    }

    @PostMapping("/{id}/accept")
    @Operation(summary = "Accept a relation hypothesis",
            description = "Promotes the hypothesis to an accepted TaxonomyRelation in the knowledge graph.")
    public ResponseEntity<Map<String, Object>> acceptHypothesis(@PathVariable Long id) {
        RepositoryContext context = currentRepositoryContext();
        try {
            RelationHypothesis accepted = hypothesisService.accept(id, context);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", accepted.getId());
            result.put("status", accepted.getStatus().name());
            result.put("sourceNodeId", accepted.getSourceNodeId());
            result.put("targetNodeId", accepted.getTargetNodeId());
            result.put("relationType", accepted.getRelationType().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a relation hypothesis",
            description = "Marks one relation hypothesis rejected in the authenticated repository context. Missing hypotheses return 404 and invalid state transitions return 400; this does not promote a relation.")
    public ResponseEntity<Map<String, Object>> rejectHypothesis(@PathVariable Long id) {
        RepositoryContext context = currentRepositoryContext();
        try {
            RelationHypothesis rejected = hypothesisService.reject(id, context);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rejected.getId());
            result.put("status", rejected.getStatus().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(error);
        }
    }

    @PostMapping("/{id}/apply-session")
    @Operation(summary = "Mark hypothesis as applied for current analysis session only",
            description = "The relationship is used in the current Architecture View and exports " +
                    "but is not permanently persisted as a TaxonomyRelation.")
    public ResponseEntity<Map<String, Object>> applyHypothesisForSession(@PathVariable Long id) {
        RepositoryContext context = currentRepositoryContext();
        try {
            RelationHypothesis hypothesis = hypothesisService.applyForSession(id, context);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", hypothesis.getId());
            result.put("appliedInCurrentAnalysis", hypothesis.isAppliedInCurrentAnalysis());
            result.put("status", hypothesis.getStatus().name());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/evidence")
    @Operation(summary = "Get evidence records for a hypothesis",
            description = "Reads retained evidence for one hypothesis in the authenticated repository context. Returns 404 when the hypothesis is unavailable in that context; no model call is made.")
    public ResponseEntity<?> getHypothesisEvidence(@PathVariable Long id,
            @RequestParam(required = false) String commitId) {
        RepositoryContext context = readRepositoryContext();
        if (commitId != null) return ResponseEntity.badRequest().build();
        try {
            return ResponseEntity.ok().header(RelationApiController.READ_MODEL_HEADER, "WORKING")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(hypothesisService.findEvidence(id, context));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private RepositoryContext readRepositoryContext() {
        return Objects.requireNonNull(workspaceResolver.resolveUnambiguousRepositoryForRead(),
                "Repository context resolver returned null for a hypothesis operation");
    }

    private RepositoryContext currentRepositoryContext() {
        // The existing workspace interceptor provisions and pins this context
        // before MVC dispatch. Resolution failures must never select shared data.
        return Objects.requireNonNull(workspaceResolver.resolveCurrentRepositoryContext(),
                "Repository context resolver returned null for a hypothesis operation");
    }
}
