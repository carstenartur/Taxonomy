package com.taxonomy.relations.controller;

import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.PendingPhase;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewPendingException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewAction;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewResult;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** Git-authoritative accept, reject and revert commands for relation proposals. */
@RestController
@RequestMapping("/api/architecture/proposals")
@Tag(name = "Git-authoritative proposal review")
public class GitProposalReviewApiController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final GitAuthoritativeProposalReviewService reviewService;
    private final WorkspaceResolver workspaceResolver;
    private final SystemRepositoryService repositoryService;
    private final RepositoryMembershipService membershipService;

    public GitProposalReviewApiController(
            GitAuthoritativeProposalReviewService reviewService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService) {
        this.reviewService = reviewService;
        this.workspaceResolver = workspaceResolver;
        this.repositoryService = repositoryService;
        this.membershipService = membershipService;
    }

    @Operation(summary = "Accept a proposal through an exact Git commit")
    @PostMapping("/{proposalId}/accept")
    public ResponseEntity<ReviewResponse> accept(
            @PathVariable Long proposalId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = true)
            String causationId,
            @RequestBody(required = false) ReviewBody body) {
        return review(
                proposalId,
                ReviewAction.ACCEPT,
                ifMatch,
                ifNoneMatch,
                causationId,
                body);
    }

    @Operation(summary = "Reject a proposal through an exact Git commit")
    @PostMapping("/{proposalId}/reject")
    public ResponseEntity<ReviewResponse> reject(
            @PathVariable Long proposalId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = true)
            String causationId,
            @RequestBody(required = false) ReviewBody body) {
        return review(
                proposalId,
                ReviewAction.REJECT,
                ifMatch,
                ifNoneMatch,
                causationId,
                body);
    }

    @Operation(summary = "Revert a reviewed proposal through an exact Git commit")
    @PostMapping("/{proposalId}/revert")
    public ResponseEntity<ReviewResponse> revert(
            @PathVariable Long proposalId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = true)
            String causationId,
            @RequestBody(required = false) ReviewBody body) {
        return review(
                proposalId,
                ReviewAction.REVERT,
                ifMatch,
                ifNoneMatch,
                causationId,
                body);
    }

    private ResponseEntity<ReviewResponse> review(
            Long proposalId,
            ReviewAction action,
            String ifMatch,
            String ifNoneMatch,
            String causationId,
            ReviewBody body) {
        try {
            RepositoryContext context = writableContext(
                    workspaceResolver.resolveCurrentRepositoryContext());
            if (context == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String expectedHead = GitHttpPrecondition.expectedHead(
                    ifMatch, ifNoneMatch);
            String rationale = body == null ? null : body.rationale();
            ReviewResult result = switch (action) {
                case ACCEPT -> reviewService.accept(
                        proposalId,
                        context,
                        expectedHead,
                        new CommandMetadata(causationId, rationale));
                case REJECT -> reviewService.reject(
                        proposalId,
                        context,
                        expectedHead,
                        new CommandMetadata(causationId, rationale));
                case REVERT -> reviewService.revert(
                        proposalId,
                        context,
                        expectedHead,
                        new CommandMetadata(causationId, rationale));
            };
            return projected(result);
        } catch (GitHttpPrecondition.PreconditionRequiredException error) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        } catch (BranchHeadConflictException error) {
            return preconditionFailed(proposalId, action, error);
        } catch (ProposalReviewPendingException error) {
            return pending(error);
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

    private static ResponseEntity<ReviewResponse> projected(
            ReviewResult result) {
        CommandResult authority = result.mutation().authority();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.ETAG,
                        GitHttpPrecondition.etag(
                                authority.authoritativeCommitId()))
                .body(ReviewResponse.projected(result));
    }

    private static ResponseEntity<ReviewResponse> pending(
            ProposalReviewPendingException error) {
        CommandResult authority = error.getAuthority();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(
                        HttpHeaders.ETAG,
                        GitHttpPrecondition.etag(
                                authority.authoritativeCommitId()))
                .body(ReviewResponse.pending(error));
    }

    private static ResponseEntity<ReviewResponse> preconditionFailed(
            Long proposalId,
            ReviewAction action,
            BranchHeadConflictException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                HttpStatus.PRECONDITION_FAILED);
        if (error.getActualHeadCommit() != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            error.getActualHeadCommit()));
        }
        return response.body(ReviewResponse.conflict(
                proposalId, action, error));
    }

    /** Convert a central read context into an explicitly authorized write context. */
    private RepositoryContext writableContext(RepositoryContext context) {
        if (context.workspaceId() != null) {
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

    private static boolean isApplicationAdmin() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(
                        authority.getAuthority()));
    }

    public record ReviewBody(String rationale) {
    }

    public record ReviewResponse(
            Long proposalId,
            String action,
            String intendedProposalStatus,
            String authoritativeCommitId,
            String changeKind,
            boolean commitCreated,
            String projectionStatus,
            String projectionOutcome,
            Boolean relationPresent,
            String pendingPhase,
            String expectedHeadCommit,
            String actualHeadCommit) {

        static ReviewResponse projected(ReviewResult result) {
            CommandResult authority = result.mutation().authority();
            return new ReviewResponse(
                    result.proposalId(),
                    result.action().name(),
                    result.proposalStatus().name(),
                    authority.authoritativeCommitId(),
                    authority.changeKind().name(),
                    authority.commitCreated(),
                    "PROJECTED",
                    result.mutation().projection().outcome().name(),
                    result.mutation().projection().relationPresent(),
                    null,
                    null,
                    null);
        }

        static ReviewResponse pending(
                ProposalReviewPendingException error) {
            CommandResult authority = error.getAuthority();
            PendingPhase phase = error.getPhase();
            return new ReviewResponse(
                    error.getProposalId(),
                    null,
                    error.getIntendedStatus().name(),
                    authority.authoritativeCommitId(),
                    authority.changeKind().name(),
                    authority.commitCreated(),
                    "PENDING_RECOVERY",
                    null,
                    null,
                    phase.name(),
                    null,
                    null);
        }

        static ReviewResponse conflict(
                Long proposalId,
                ReviewAction action,
                BranchHeadConflictException error) {
            return new ReviewResponse(
                    proposalId,
                    action.name(),
                    null,
                    null,
                    null,
                    false,
                    "PRECONDITION_FAILED",
                    null,
                    null,
                    null,
                    error.getExpectedHeadCommit(),
                    error.getActualHeadCommit());
        }
    }
}
