package com.taxonomy.relations.controller;

import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewConflictException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewProjectionPendingException;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.ProposalReviewDecision;
import com.taxonomy.relations.service.RelationProposalReviewCandidateReader.ProposalReviewNotFoundException;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Locale;

/** Git-authoritative human review API for relation proposals. */
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

    @Operation(summary = "Accept or reject a proposal through an exact Git commit")
    @PutMapping("/{proposalId}/review")
    public ResponseEntity<ReviewResponse> review(
            @PathVariable Long proposalId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false)
            String causationId,
            @RequestBody ReviewRequest request) {
        try {
            RepositoryContext context = writableContext(
                    workspaceResolver.resolveCurrentRepositoryContext());
            if (context == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String expectedHead = GitHttpPrecondition.expectedHead(
                    ifMatch, ifNoneMatch);
            ProposalReviewDecision decision = ProposalReviewDecision.valueOf(
                    request.decision().strip().toUpperCase(Locale.ROOT));
            var result = reviewService.review(
                    context,
                    proposalId,
                    expectedHead,
                    causationId,
                    decision,
                    request.rationale());
            return ResponseEntity.ok()
                    .header(
                            HttpHeaders.ETAG,
                            GitHttpPrecondition.etag(
                                    result.authority().authoritativeCommitId()))
                    .body(ReviewResponse.projected(result));
        } catch (GitHttpPrecondition.PreconditionRequiredException error) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        } catch (BranchHeadConflictException error) {
            return preconditionFailed(error);
        } catch (ProposalReviewNotFoundException error) {
            return ResponseEntity.notFound().build();
        } catch (ProjectionPendingException error) {
            return pending(proposalId, error.getAuthority());
        } catch (ProposalReviewProjectionPendingException error) {
            return pending(error.getProposalId(), error.getAuthority());
        } catch (ReadOnlyRepositoryContextException error) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (ProposalReviewConflictException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IOException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private static ResponseEntity<ReviewResponse> pending(
            Long proposalId,
            CommandResult authority) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(
                        HttpHeaders.ETAG,
                        GitHttpPrecondition.etag(
                                authority.authoritativeCommitId()))
                .body(ReviewResponse.pending(proposalId, authority));
    }

    private static ResponseEntity<ReviewResponse> preconditionFailed(
            BranchHeadConflictException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                HttpStatus.PRECONDITION_FAILED);
        if (error.getActualHeadCommit() != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            error.getActualHeadCommit()));
        }
        return response.body(new ReviewResponse(
                null,
                null,
                error.getBranch(),
                null,
                null,
                null,
                "PRECONDITION_FAILED",
                null,
                error.getExpectedHeadCommit(),
                error.getActualHeadCommit()));
    }

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

    public record ReviewRequest(String decision, String rationale) {
        public ReviewRequest {
            if (decision == null || decision.isBlank()) {
                throw new IllegalArgumentException("decision must not be blank");
            }
        }
    }

    public record ReviewResponse(
            Long proposalId,
            String decision,
            String branch,
            String authoritativeCommitId,
            String proposalStatus,
            String relationProjectionOutcome,
            String projectionStatus,
            String proposalProjectionOutcome,
            String expectedHeadCommit,
            String actualHeadCommit) {

        static ReviewResponse projected(
                GitAuthoritativeProposalReviewService.ReviewResult result) {
            return new ReviewResponse(
                    result.proposalProjection().proposalId(),
                    result.decision().name(),
                    result.authority().branch(),
                    result.authority().authoritativeCommitId(),
                    result.proposalProjection().status().name(),
                    result.relationProjection().outcome().name(),
                    "PROJECTED",
                    result.proposalProjection().outcome().name(),
                    null,
                    null);
        }

        static ReviewResponse pending(
                Long proposalId,
                CommandResult authority) {
            return new ReviewResponse(
                    proposalId,
                    null,
                    authority.branch(),
                    authority.authoritativeCommitId(),
                    null,
                    null,
                    "PENDING_REBUILD",
                    null,
                    null,
                    null);
        }
    }
}
