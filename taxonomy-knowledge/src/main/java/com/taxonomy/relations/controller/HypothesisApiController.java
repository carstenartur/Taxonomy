package com.taxonomy.relations.controller;

import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
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

    public HypothesisApiController(HypothesisService hypothesisService, WorkspaceResolver workspaceResolver) {
        this.hypothesisService = hypothesisService;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping
    @Operation(summary = "List relation hypotheses, optionally filtered by status")
    public ResponseEntity<List<RelationHypothesis>> listHypotheses(
            @RequestParam(required = false) HypothesisStatus status) {
        RepositoryContext context = currentRepositoryContext();
        List<RelationHypothesis> result = status != null
                ? hypothesisService.findByStatus(status, context)
                : hypothesisService.findAll(context);
        return ResponseEntity.ok(result);
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
    @Operation(summary = "Reject a relation hypothesis")
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
    @Operation(summary = "Get evidence records for a hypothesis")
    public ResponseEntity<?> getHypothesisEvidence(@PathVariable Long id) {
        RepositoryContext context = currentRepositoryContext();
        try {
            return ResponseEntity.ok(hypothesisService.findEvidence(id, context));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private RepositoryContext currentRepositoryContext() {
        // The existing workspace interceptor provisions and pins this context
        // before MVC dispatch. Resolution failures must never select shared data.
        return Objects.requireNonNull(workspaceResolver.resolveCurrentRepositoryContext(),
                "Repository context resolver returned null for a hypothesis operation");
    }
}
