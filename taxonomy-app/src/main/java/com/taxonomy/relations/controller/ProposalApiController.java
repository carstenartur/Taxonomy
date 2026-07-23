package com.taxonomy.relations.controller;

import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.relations.service.RelationReviewService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST API for workspace-scoped relation proposals and review. */
@RestController
@RequestMapping("/api")
@Tag(name = "Proposals")
public class ProposalApiController {

    private final RelationProposalService proposalService;
    private final RelationReviewService reviewService;
    private final WorkspaceContextResolver contextResolver;

    public ProposalApiController(RelationProposalService proposalService,
                                 RelationReviewService reviewService,
                                 WorkspaceContextResolver contextResolver) {
        this.proposalService = proposalService;
        this.reviewService = reviewService;
        this.contextResolver = contextResolver;
    }

    @Operation(summary = "Propose relations")
    @PostMapping("/proposals/propose")
    public ResponseEntity<List<RelationProposalDto>> proposeRelations(
            @RequestBody Map<String, String> body) {
        String sourceCode = body.get("sourceCode");
        String relationTypeText = body.get("relationType");
        if (sourceCode == null || sourceCode.isBlank()
                || relationTypeText == null || relationTypeText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(relationTypeText.toUpperCase());
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }

        int limit = parseLimit(body.getOrDefault("limit", "10"));
        try {
            return ResponseEntity.ok(
                    proposalService.proposeRelations(sourceCode, relationType, limit));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "List all proposals")
    @GetMapping("/proposals")
    public ResponseEntity<List<RelationProposalDto>> getAllProposals() {
        return ResponseEntity.ok(proposalService.getAllProposals());
    }

    @Operation(summary = "List pending proposals")
    @GetMapping("/proposals/pending")
    public ResponseEntity<List<RelationProposalDto>> getPendingProposals() {
        return ResponseEntity.ok(proposalService.getPendingProposals());
    }

    @Operation(summary = "List node proposals")
    @GetMapping("/node/{code}/proposals")
    public ResponseEntity<List<RelationProposalDto>> getProposalsForNode(
            @PathVariable String code) {
        return ResponseEntity.ok(proposalService.getProposalsForNode(code));
    }

    @Operation(summary = "Accept proposal")
    @PostMapping("/proposals/{id}/accept")
    public ResponseEntity<TaxonomyRelationDto> acceptProposal(@PathVariable Long id) {
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        try {
            return ResponseEntity.ok(reviewService.acceptProposal(id, context));
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Reject proposal")
    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<RelationProposalDto> rejectProposal(@PathVariable Long id) {
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        try {
            return ResponseEntity.ok(reviewService.rejectProposal(id, context));
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Create proposal from hypothesis")
    @PostMapping("/proposals/from-hypothesis")
    public ResponseEntity<RelationProposalDto> createFromHypothesis(
            @RequestBody Map<String, Object> body) {
        String sourceCode = body.get("sourceCode") instanceof String value ? value : null;
        String targetCode = body.get("targetCode") instanceof String value ? value : null;
        String relationTypeText = body.get("relationType") instanceof String value ? value : null;
        Number confidenceNumber = body.get("confidence") instanceof Number value ? value : null;
        String rationale = body.get("rationale") instanceof String value ? value : null;
        if (sourceCode == null || sourceCode.isBlank()
                || targetCode == null || targetCode.isBlank()
                || relationTypeText == null || relationTypeText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(relationTypeText.toUpperCase());
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }

        double confidence = confidenceNumber != null ? confidenceNumber.doubleValue() : 0.5;
        try {
            RelationProposalDto proposal = proposalService.createFromHypothesis(
                    sourceCode, targetCode, relationType, confidence, rationale);
            return proposal != null
                    ? ResponseEntity.ok(proposal)
                    : ResponseEntity.status(409).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Revert proposal")
    @PostMapping("/proposals/{id}/revert")
    public ResponseEntity<RelationProposalDto> revertProposal(@PathVariable Long id) {
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        try {
            return ResponseEntity.ok(reviewService.revertProposal(id, context));
        } catch (IllegalArgumentException | IllegalStateException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "Bulk action on proposals")
    @PostMapping("/proposals/bulk")
    public ResponseEntity<Map<String, Object>> bulkAction(
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = body.get("ids") instanceof List<?> list
                ? (List<Number>) list : null;
        String action = body.get("action") instanceof String value ? value : null;
        if (ids == null || ids.isEmpty() || action == null || action.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        WorkspaceContext context = contextResolver.resolveCurrentContext();
        int success = 0;
        int failed = 0;
        for (Number idNumber : ids) {
            if (idNumber == null) {
                failed++;
                continue;
            }
            try {
                if ("ACCEPT".equalsIgnoreCase(action)) {
                    reviewService.acceptProposal(idNumber.longValue(), context);
                } else if ("REJECT".equalsIgnoreCase(action)) {
                    reviewService.rejectProposal(idNumber.longValue(), context);
                } else {
                    return ResponseEntity.badRequest().build();
                }
                success++;
            } catch (IllegalArgumentException | IllegalStateException error) {
                failed++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("success", success);
        result.put("failed", failed);
        result.put("total", ids.size());
        return ResponseEntity.ok(result);
    }

    private static int parseLimit(String value) {
        try {
            int limit = Integer.parseInt(value);
            return limit >= 1 && limit <= 100 ? limit : 10;
        } catch (NumberFormatException error) {
            return 10;
        }
    }
}