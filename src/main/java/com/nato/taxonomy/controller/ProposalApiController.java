package com.nato.taxonomy.controller;

import com.nato.taxonomy.dto.RelationProposalDto;
import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.service.RelationProposalService;
import com.nato.taxonomy.service.RelationReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    @GetMapping("/proposals")
    public ResponseEntity<List<RelationProposalDto>> getAllProposals() {
        return ResponseEntity.ok(proposalService.getAllProposals());
    }

    /**
     * List pending proposals (review queue).
     */
    @GetMapping("/proposals/pending")
    public ResponseEntity<List<RelationProposalDto>> getPendingProposals() {
        return ResponseEntity.ok(proposalService.getPendingProposals());
    }

    /**
     * List proposals for a specific source node.
     */
    @GetMapping("/node/{code}/proposals")
    public ResponseEntity<List<RelationProposalDto>> getProposalsForNode(
            @PathVariable String code) {
        return ResponseEntity.ok(proposalService.getProposalsForNode(code));
    }

    /**
     * Accept a pending proposal — creates the actual TaxonomyRelation.
     */
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
    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<RelationProposalDto> rejectProposal(@PathVariable Long id) {
        try {
            RelationProposalDto dto = reviewService.rejectProposal(id);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
