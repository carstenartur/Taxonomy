package com.taxonomy.relations.controller;

import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewPendingException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewAction;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewResult;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationProjectionUnavailableException;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** REST API for repository/workspace-scoped relation proposals and review. */
@RestController
@RequestMapping("/api")
@Tag(name = "Proposals")
public class ProposalApiController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RelationProposalService proposalService;
    private final GitAuthoritativeProposalReviewService reviewService;
    private final RelationBranchProjectionReadinessService readinessService;
    private final WorkspaceResolver workspaceResolver;
    private final SystemRepositoryService repositoryService;
    private final RepositoryMembershipService membershipService;

    public ProposalApiController(
            RelationProposalService proposalService,
            GitAuthoritativeProposalReviewService reviewService,
            RelationBranchProjectionReadinessService readinessService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService) {
        this.proposalService = proposalService;
        this.reviewService = reviewService;
        this.readinessService = readinessService;
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
        } catch (RelationProjectionUnavailableException error) {
            return projectionUnavailable(error).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    @Operation(summary = "List all proposals")
    @GetMapping("/proposals")
    public ResponseEntity<List<RelationProposalDto>> getAllProposals() {
        RepositoryContext context = workspaceResolver
                .resolveCurrentRepositoryContext();
        return ResponseEntity.ok(
                proposalService.getAllProposalsInContext(context));
    }

    @Operation(summary = "List pending proposals")
    @GetMapping("/proposals/pending")
    public ResponseEntity<List<RelationProposalDto>> getPendingProposals() {
        RepositoryContext context = workspaceResolver
                .resolveCurrentRepositoryContext();
        return ResponseEntity.ok(
                proposalService.getPendingProposalsInContext(context));
    }

    @Operation(summary = "List node proposals")
    @GetMapping("/node/{code}/proposals")
    public ResponseEntity<List<RelationProposalDto>> getProposalsForNode(
            @PathVariable String code) {
        RepositoryContext context = workspaceResolver
                .resolveCurrentRepositoryContext();
        return ResponseEntity.ok(
                proposalService.getProposalsForNodeInContext(code, context));
    }

    /** Compatibility overload used by focused unit tests and in-process callers. */
    public ResponseEntity<Map<String, Object>> acceptProposal(Long id) {
        return acceptProposal(id, null);
    }

    @Operation(summary = "Accept proposal through an authoritative Git commit")
    @PostMapping("/proposals/{id}/accept")
    public ResponseEntity<Map<String, Object>> acceptProposal(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey) {
        return reviewProposal(id, ReviewAction.ACCEPT, ifMatch, idempotencyKey);
    }

    /** Compatibility overload used by focused unit tests and in-process callers. */
    public ResponseEntity<Map<String, Object>> rejectProposal(Long id) {
        return rejectProposal(id, null);
    }

    @Operation(summary = "Reject proposal through an authoritative Git commit")
    @PostMapping("/proposals/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectProposal(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey) {
        return reviewProposal(id, ReviewAction.REJECT, ifMatch, idempotencyKey);
    }

    @Operation(summary = "Create proposal from hypothesis")
    @PostMapping("/proposals/from-hypothesis")
    public ResponseEntity<RelationProposalDto> createFromHypothesis(
            @RequestBody Map<String, Object> body) {
        String sourceCode = body.get("sourceCode") instanceof String value
                ? value : null;
        String targetCode = body.get("targetCode") instanceof String value
                ? value : null;
        String relationTypeText = body.get("relationType") instanceof String value
                ? value : null;
        Number confidenceNumber = body.get("confidence") instanceof Number value
                ? value : null;
        String rationale = body.get("rationale") instanceof String value
                ? value : null;
        if (sourceCode == null || sourceCode.isBlank()
                || targetCode == null || targetCode.isBlank()
                || relationTypeText == null || relationTypeText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        RelationType relationType;
        try {
            relationType = RelationType.valueOf(
                    relationTypeText.toUpperCase());
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }

        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        double confidence = confidenceNumber != null
                ? confidenceNumber.doubleValue() : 0.5;
        try {
            RelationProposalDto proposal =
                    proposalService.createFromHypothesisInContext(
                            sourceCode,
                            targetCode,
                            relationType,
                            confidence,
                            rationale,
                            context);
            return proposal != null
                    ? ResponseEntity.ok(proposal)
                    : ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (RelationProjectionUnavailableException error) {
            return projectionUnavailable(error).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        }
    }

    /** Compatibility overload used by focused unit tests and in-process callers. */
    public ResponseEntity<Map<String, Object>> revertProposal(Long id) {
        return revertProposal(id, null);
    }

    @Operation(summary = "Revert proposal through an authoritative Git commit")
    @PostMapping("/proposals/{id}/revert")
    public ResponseEntity<Map<String, Object>> revertProposal(
            @PathVariable Long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey) {
        return reviewProposal(id, ReviewAction.REVERT, ifMatch, idempotencyKey);
    }

    /** Compatibility overload used by focused unit tests and in-process callers. */
    public ResponseEntity<Map<String, Object>> bulkAction(
            Map<String, Object> body) {
        return bulkAction(body, null);
    }

    @Operation(summary = "Ordered Git-first bulk action on proposals")
    @PostMapping("/proposals/bulk")
    public ResponseEntity<Map<String, Object>> bulkAction(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false)
            String idempotencyKey) {
        @SuppressWarnings("unchecked")
        List<Number> ids = body.get("ids") instanceof List<?> list
                ? (List<Number>) list : null;
        String actionText = body.get("action") instanceof String value
                ? value : null;
        if (ids == null || ids.isEmpty()
                || actionText == null || actionText.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        ReviewAction action;
        if ("ACCEPT".equalsIgnoreCase(actionText)) {
            action = ReviewAction.ACCEPT;
        } else if ("REJECT".equalsIgnoreCase(actionText)) {
            action = ReviewAction.REJECT;
        } else {
            return ResponseEntity.badRequest().build();
        }

        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String expectedHead = currentHead(context);
        if (expectedHead == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .header(RelationApiController.PROJECTION_STATE_HEADER,
                            ReadinessState.BRANCH_MISSING.name())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .build();
        }

        String bulkKey = normalizeIdempotencyKey(idempotencyKey);
        if (bulkKey == null) {
            bulkKey = "legacy-proposal-bulk-"
                    + action.name().toLowerCase(Locale.ROOT)
                    + "-" + expectedHead + "-" + ids.hashCode();
        }

        List<Map<String, Object>> itemResults = new ArrayList<>();
        int projected = 0;
        int pending = 0;
        int failed = 0;
        boolean stop = false;
        for (int index = 0; index < ids.size() && !stop; index++) {
            Number idNumber = ids.get(index);
            if (idNumber == null) {
                failed++;
                itemResults.add(itemFailure(null, "INVALID_ID", null));
                continue;
            }
            long proposalId = idNumber.longValue();
            String itemKey = bulkKey + ":" + index + ":" + proposalId;
            try {
                ReviewResult result = executeReview(
                        proposalId,
                        action,
                        context,
                        expectedHead,
                        itemKey);
                CommandResult authority = result.mutation().authority();
                expectedHead = authority.authoritativeCommitId();
                projected++;
                itemResults.add(reviewPayload(result, "PROJECTED"));
            } catch (ProposalReviewPendingException error) {
                expectedHead = error.getAuthority().authoritativeCommitId();
                pending++;
                itemResults.add(pendingPayload(error));
                stop = true;
            } catch (BranchHeadConflictException error) {
                failed++;
                if (error.getActualHeadCommit() != null) {
                    expectedHead = error.getActualHeadCommit();
                }
                itemResults.add(itemFailure(
                        proposalId,
                        "PRECONDITION_FAILED",
                        error.getMessage()));
                stop = true;
            } catch (IllegalArgumentException | IllegalStateException error) {
                failed++;
                itemResults.add(itemFailure(
                        proposalId,
                        "REVIEW_REJECTED",
                        error.getMessage()));
            } catch (IOException error) {
                failed++;
                itemResults.add(itemFailure(
                        proposalId,
                        "GIT_UNAVAILABLE",
                        error.getMessage()));
                stop = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action.name());
        result.put("total", ids.size());
        result.put("processed", itemResults.size());
        result.put("projected", projected);
        result.put("success", projected); // legacy alias for "projected" — kept for browser UI compatibility
        result.put("pendingRecovery", pending);
        result.put("failed", failed);
        result.put("complete", itemResults.size() == ids.size()
                && pending == 0 && failed == 0);
        result.put("authoritativeCommitId", expectedHead);
        result.put("items", itemResults);

        HttpStatus status = pending > 0
                ? HttpStatus.ACCEPTED
                : failed > 0 ? HttpStatus.MULTI_STATUS : HttpStatus.OK;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (expectedHead != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(expectedHead));
        }
        return response.body(result);
    }

    private ResponseEntity<Map<String, Object>> reviewProposal(
            Long proposalId,
            ReviewAction action,
            String ifMatch,
            String suppliedIdempotencyKey) {
        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            String expectedHead = expectedHead(context, ifMatch);
            if (expectedHead == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .header(RelationApiController.PROJECTION_STATE_HEADER,
                                ReadinessState.BRANCH_MISSING.name())
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .build();
            }
            String causationId = normalizeIdempotencyKey(suppliedIdempotencyKey);
            if (causationId == null) {
                causationId = "legacy-proposal-"
                        + action.name().toLowerCase(Locale.ROOT)
                        + "-" + proposalId + "-" + expectedHead;
            }
            ReviewResult result = executeReview(
                    proposalId,
                    action,
                    context,
                    expectedHead,
                    causationId);
            CommandResult authority = result.mutation().authority();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(
                            HttpHeaders.ETAG,
                            GitHttpPrecondition.etag(
                                    authority.authoritativeCommitId()))
                    .body(reviewPayload(result, "PROJECTED"));
        } catch (BranchHeadConflictException error) {
            ResponseEntity.BodyBuilder response = ResponseEntity.status(
                    HttpStatus.PRECONDITION_FAILED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store");
            if (error.getActualHeadCommit() != null) {
                response.header(
                        HttpHeaders.ETAG,
                        GitHttpPrecondition.etag(error.getActualHeadCommit()));
            }
            return response.body(conflictPayload(proposalId, action, error));
        } catch (ProposalReviewPendingException error) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(
                            HttpHeaders.ETAG,
                            GitHttpPrecondition.etag(
                                    error.getAuthority().authoritativeCommitId()))
                    .body(pendingPayload(error));
        } catch (ReadOnlyRepositoryContextException error) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IOException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private ReviewResult executeReview(
            Long proposalId,
            ReviewAction action,
            RepositoryContext context,
            String expectedHead,
            String causationId) throws IOException {
        CommandMetadata metadata = new CommandMetadata(
                causationId,
                "Git-first proposal review through the productive API");
        return switch (action) {
            case ACCEPT -> reviewService.accept(
                    proposalId, context, expectedHead, metadata);
            case REJECT -> reviewService.reject(
                    proposalId, context, expectedHead, metadata);
            case REVERT -> reviewService.revert(
                    proposalId, context, expectedHead, metadata);
        };
    }

    private String expectedHead(RepositoryContext context, String ifMatch) {
        if (ifMatch != null && !ifMatch.isBlank()) {
            return GitHttpPrecondition.expectedHead(ifMatch, null);
        }
        return currentHead(context);
    }

    private String currentHead(RepositoryContext context) {
        return readinessService.readCurrentHead(context);
    }

    private static Map<String, Object> reviewPayload(
            ReviewResult result,
            String projectionStatus) {
        CommandResult authority = result.mutation().authority();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", result.proposalId());
        payload.put("action", result.action().name());
        payload.put("status", result.proposalStatus().name());
        payload.put("authoritativeCommitId",
                authority.authoritativeCommitId());
        payload.put("previousHeadCommit", authority.previousHeadCommit());
        payload.put("changeKind", authority.changeKind().name());
        payload.put("commitCreated", authority.commitCreated());
        payload.put("projectionStatus", projectionStatus);
        payload.put("projectionOutcome",
                result.mutation().projection().outcome().name());
        payload.put("relationPresent",
                result.mutation().projection().relationPresent());
        return payload;
    }

    private static Map<String, Object> pendingPayload(
            ProposalReviewPendingException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", error.getProposalId());
        payload.put("status", error.getIntendedStatus().name());
        payload.put("authoritativeCommitId",
                error.getAuthority().authoritativeCommitId());
        payload.put("changeKind", error.getAuthority().changeKind().name());
        payload.put("commitCreated", error.getAuthority().commitCreated());
        payload.put("projectionStatus", "PENDING_RECOVERY");
        payload.put("pendingPhase", error.getPhase().name());
        return payload;
    }

    private static Map<String, Object> conflictPayload(
            Long proposalId,
            ReviewAction action,
            BranchHeadConflictException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", proposalId);
        payload.put("action", action.name());
        payload.put("projectionStatus", "PRECONDITION_FAILED");
        payload.put("expectedHeadCommit", error.getExpectedHeadCommit());
        payload.put("actualHeadCommit", error.getActualHeadCommit());
        return payload;
    }

    private static Map<String, Object> itemFailure(
            Long proposalId,
            String code,
            String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("proposalId", proposalId);
        payload.put("projectionStatus", code);
        if (detail != null && !detail.isBlank()) {
            payload.put("detail", detail);
        }
        return payload;
    }

    /** Convert a central read context into an explicitly authorized write context. */
    private RepositoryContext writableContext(RepositoryContext context) {
        if (context.scope() == RepositoryScope.WORKSPACE
                || context.scope() == RepositoryScope.FORK) {
            return context;
        }
        SystemRepository repository = repositoryService.getRepository(
                context.repositoryId());
        if (!isApplicationAdmin()
                && !membershipService.canMaintain(
                        repository, context.username())) {
            return null;
        }
        return new RepositoryContext(
                context.repositoryId(),
                null,
                context.branch(),
                context.username(),
                RepositoryScope.CENTRAL_WRITE);
    }

    private static ResponseEntity.BodyBuilder projectionUnavailable(
            RelationProjectionUnavailableException error) {
        HttpStatus status = error.getReadinessState()
                == ReadinessState.BRANCH_MISSING
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status)
                .header(
                        RelationApiController.PROJECTION_STATE_HEADER,
                        error.getReadinessState().name())
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (error.getPendingRecoveryCount() > 0) {
            response.header(
                    RelationApiController.PENDING_RECOVERY_HEADER,
                    String.valueOf(error.getPendingRecoveryCount()));
        }
        if (error.getCurrentHeadCommit() != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            error.getCurrentHeadCommit()));
        }
        return response;
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must be one line");
        }
        return normalized;
    }

    private static boolean isApplicationAdmin() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(
                                authority.getAuthority()));
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
