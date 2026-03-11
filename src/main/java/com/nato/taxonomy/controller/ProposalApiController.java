package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.RelationProposalDto;
import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.service.RelationProposalService;
import com.nato.taxonomy.service.RelationReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for the Relation Proposal Pipeline.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/proposals/propose} — trigger proposal generation</li>
 *   <li>{@code GET  /api/proposals} — list all proposals (optionally filter by status)</li>
 *   <li>{@code GET  /api/proposals/pending} — list pending proposals</li>
 *   <li>{@code GET  /api/node/{code}/proposals} — proposals for a specific node</li>
 *   <li>{@code POST /api/proposals/{id}/accept} — accept a proposal</li>
 *   <li>{@code POST /api/proposals/{id}/reject} — reject a proposal</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Proposals")
public class ProposalApiController {

    private final RelationProposalService proposalService;
    private final RelationReviewService reviewService;

    public ProposalApiController(RelationProposalService proposalService,
                                  RelationReviewService reviewService) {
        this.proposalService = proposalService;
        this.reviewService = reviewService;
    }

    /**
     * Trigger the proposal pipeline for a source node and relation type.
     */
    @Operation(summary = "Propose relations", description = "Trigger the proposal pipeline for a source node and relation type")
    @PostMapping("/proposals/propose")
    public ResponseEntity<List<RelationProposalDto>> proposeRelations(
            @RequestBody Map<String, String> body) {
        String sourceCode = body.get("sourceCode");
        String relationTypeStr = body.get("relationType");
        String limitStr = body.getOrDefault("limit", "10");

        if (sourceCode == null || sourceCode.isBlank() ||
                relationTypeStr == null || relationTypeStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(relationTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        int limit;
        try {
            limit = Integer.parseInt(limitStr);
            if (limit < 1 || limit > 100) limit = 10;
        } catch (NumberFormatException e) {
            limit = 10;
        }

        try {
            List<RelationProposalDto> proposals =
                    proposalService.proposeRelations(sourceCode, relationType, limit);
            return ResponseEntity.ok(proposals);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * List all proposals.
     */
    @Operation(summary = "List all proposals", description = "Returns all relation proposals")
    @GetMapping("/proposals")
    public ResponseEntity<List<RelationProposalDto>> getAllProposals() {
        return ResponseEntity.ok(proposalService.getAllProposals());
    }

    /**
     * List pending proposals (review queue).
     */
    @Operation(summary = "List pending proposals", description = "Returns all proposals with PENDING status (review queue)")
    @GetMapping("/proposals/pending")
    public ResponseEntity<List<RelationProposalDto>> getPendingProposals() {
        return ResponseEntity.ok(proposalService.getPendingProposals());
    }

    /**
     * List proposals for a specific source node.
     */
    @Operation(summary = "List node proposals", description = "Returns all proposals for a specific source node")
    @GetMapping("/node/{code}/proposals")
    public ResponseEntity<List<RelationProposalDto>> getProposalsForNode(
            @PathVariable String code) {
        return ResponseEntity.ok(proposalService.getProposalsForNode(code));
    }

    /**
     * Accept a pending proposal — creates the actual TaxonomyRelation.
     */
    @Operation(summary = "Accept proposal", description = "Accepts a pending proposal and creates the actual taxonomy relation")
    @PostMapping("/proposals/{id}/accept")
    public ResponseEntity<TaxonomyRelationDto> acceptProposal(@PathVariable Long id) {
        try {
            TaxonomyRelationDto relation = reviewService.acceptProposal(id);
            return ResponseEntity.ok(relation);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Reject a pending proposal.
     */
    @Operation(summary = "Reject proposal", description = "Rejects a pending proposal")
    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<RelationProposalDto> rejectProposal(@PathVariable Long id) {
        try {
            RelationProposalDto dto = reviewService.rejectProposal(id);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Create a proposal directly from an analysis hypothesis.
     * Request body: {@code { "sourceCode": "CP", "targetCode": "CR",
     *   "relationType": "REALIZES", "confidence": 0.56, "rationale": "..." }}
     */
    @Operation(summary = "Create proposal from hypothesis",
            description = "Creates a proposal from an AI-generated relation hypothesis")
    @PostMapping("/proposals/from-hypothesis")
    public ResponseEntity<RelationProposalDto> createFromHypothesis(@RequestBody Map<String, Object> body) {
        String sourceCode = (String) body.get("sourceCode");
        String targetCode = (String) body.get("targetCode");
        String relationTypeStr = (String) body.get("relationType");
        Number confidenceNum = (Number) body.get("confidence");
        String rationale = (String) body.get("rationale");

        if (sourceCode == null || sourceCode.isBlank()
                || targetCode == null || targetCode.isBlank()
                || relationTypeStr == null || relationTypeStr.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(relationTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        double confidence = confidenceNum != null ? confidenceNum.doubleValue() : 0.5;

        try {
            RelationProposalDto dto = proposalService.createFromHypothesis(
                    sourceCode, targetCode, relationType, confidence, rationale);
            if (dto == null) {
                // Proposal already exists
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("message", "Proposal already exists for this source, target, and relation type");
                return ResponseEntity.status(409).body(null);
            }
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Revert a proposal back to PENDING status (undo accept/reject).
     * If the proposal was accepted, the corresponding relation is deleted.
     */
    @Operation(summary = "Revert proposal", description = "Reverts a proposal back to PENDING status (undo last action)")
    @PostMapping("/proposals/{id}/revert")
    public ResponseEntity<RelationProposalDto> revertProposal(@PathVariable Long id) {
        try {
            RelationProposalDto dto = reviewService.revertProposal(id);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Bulk accept or reject multiple proposals.
     * Request body: {@code { "ids": [1, 2, 3], "action": "ACCEPT" | "REJECT" }}
     */
    @Operation(summary = "Bulk action on proposals", description = "Accept or reject multiple proposals at once")
    @PostMapping("/proposals/bulk")
    public ResponseEntity<Map<String, Object>> bulkAction(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> ids = (List<Number>) body.get("ids");
        String action = (String) body.get("action");

        if (ids == null || ids.isEmpty() || action == null || action.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        int success = 0;
        int failed = 0;

        for (Number idNum : ids) {
            Long id = idNum.longValue();
            try {
                if ("ACCEPT".equalsIgnoreCase(action)) {
                    reviewService.acceptProposal(id);
                } else if ("REJECT".equalsIgnoreCase(action)) {
                    reviewService.rejectProposal(id);
                } else {
                    return ResponseEntity.badRequest().build();
                }
                success++;
            } catch (IllegalArgumentException | IllegalStateException e) {
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
}
