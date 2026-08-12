package com.taxonomy.relations.controller;

import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API for repository/workspace-scoped proposal generation and reads.
 *
 * <p>Proposal decisions are Git-authoritative and therefore live under
 * {@code /api/architecture/proposals}. The historic DB-first review routes
 * remain only as explicit HTTP 410 migration guards.</p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Proposals")
public class ProposalApiController {

    private final RelationProposalService proposalService;
    private final WorkspaceResolver workspaceResolver;
    private final SystemRepositoryService repositoryService;
    private final RepositoryMembershipService membershipService;

    public ProposalApiController(
            RelationProposalService proposalService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService) {
        this.proposalService = proposalService;
        this.workspaceResolver = workspaceResolver;
        this.repositoryService = repositoryService;
        this.membershipService = membershipService;
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

        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        int limit = parseLimit(body.getOrDefault("limit", "10"));
        try {
            return ResponseEntity.ok(proposalService.proposeRelationsInContext(
                    sourceCode, relationType, limit, context));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "List all proposals")
    @GetMapping("/proposals")
    public ResponseEntity<List<RelationProposalDto>> getAllProposals() {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(proposalService.getAllProposalsInContext(context));
    }

    @Operation(summary = "List pending proposals")
    @GetMapping("/proposals/pending")
    public ResponseEntity<List<RelationProposalDto>> getPendingProposals() {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(proposalService.getPendingProposalsInContext(context));
    }

    @Operation(summary = "List node proposals")
    @GetMapping("/node/{code}/proposals")
    public ResponseEntity<List<RelationProposalDto>> getProposalsForNode(
            @PathVariable String code) {
        RepositoryContext context = workspaceResolver.resolveCurrentRepositoryContext();
        return ResponseEntity.ok(
                proposalService.getProposalsForNodeInContext(code, context));
    }

    /**
     * DB-first acceptance is retired. Call the Git-authoritative endpoint with
     * an exact branch precondition and an idempotency key.
     */
    @Deprecated(forRemoval = true)
    @Operation(
            summary = "Retired DB-first accept route",
            description = "Use POST /api/architecture/proposals/{proposalId}/accept")
    @PostMapping("/proposals/{id}/accept")
    public ResponseEntity<Void> acceptProposal(
            @PathVariable("id") Long ignoredId) {
        return ResponseEntity.status(HttpStatus.GONE).build();
    }

    /** DB-first rejection is retired in favor of a Git relation decision. */
    @Deprecated(forRemoval = true)
    @Operation(
            summary = "Retired DB-first reject route",
            description = "Use POST /api/architecture/proposals/{proposalId}/reject")
    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<Void> rejectProposal(
            @PathVariable("id") Long ignoredId) {
        return ResponseEntity.status(HttpStatus.GONE).build();
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

        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        double confidence = confidenceNumber != null ? confidenceNumber.doubleValue() : 0.5;
        try {
            RelationProposalDto proposal = proposalService.createFromHypothesisInContext(
                    sourceCode,
                    targetCode,
                    relationType,
                    confidence,
                    rationale,
                    context);
            return proposal != null
                    ? ResponseEntity.ok(proposal)
                    : ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** DB-first revert is retired in favor of a Git removal command. */
    @Deprecated(forRemoval = true)
    @Operation(
            summary = "Retired DB-first revert route",
            description = "Use POST /api/architecture/proposals/{proposalId}/revert")
    @PostMapping("/proposals/{id}/revert")
    public ResponseEntity<Void> revertProposal(
            @PathVariable("id") Long ignoredId) {
        return ResponseEntity.status(HttpStatus.GONE).build();
    }

    /**
     * The former bulk endpoint executed independent database transactions with
     * no Git-head precondition. Clients must sequence the Git-authoritative
     * single-proposal commands and advance the returned ETag after every commit.
     */
    @Deprecated(forRemoval = true)
    @Operation(
            summary = "Retired DB-first bulk review route",
            description = "Sequence the Git-authoritative single-proposal endpoints")
    @PostMapping("/proposals/bulk")
    public ResponseEntity<Void> bulkAction(
            @RequestBody(required = false) Map<String, Object> ignoredBody) {
        return ResponseEntity.status(HttpStatus.GONE).build();
    }

    /** Preserve isolated write scopes; authorize central reads before upgrading. */
    private RepositoryContext writableContext(RepositoryContext context) {
        if (context.scope() == RepositoryScope.WORKSPACE
                || context.scope() == RepositoryScope.FORK) {
            return context;
        }
        SystemRepository repository = repositoryService.getRepository(context.repositoryId());
        if (!isApplicationAdmin()
                && !membershipService.canMaintain(repository, context.username())) {
            return null;
        }
        return new RepositoryContext(
                context.repositoryId(),
                null,
                context.branch(),
                context.username(),
                RepositoryScope.CENTRAL_WRITE);
    }

    private static boolean isApplicationAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
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
