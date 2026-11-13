package com.taxonomy.relations.controller;

import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionSourceException;
import com.taxonomy.relations.service.RelationProjectionOperationsService;
import com.taxonomy.relations.service.BranchGraphVectors;
import com.taxonomy.relations.service.RelationProjectionOperationsService.ProjectionStatus;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildHeadConflictException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildVerificationException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RecoveryReconciliationPendingException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.RecoveryRecord;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.versioning.service.HistoryIndexJournal;
import com.taxonomy.versioning.service.HistoryIndexMaintenance;
import com.taxonomy.workspace.service.DerivedProjection;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** Authorized operator API for rebuildable relation branch projections. */
@RestController
@RequestMapping("/api/architecture/relations/projection")
@Tag(name = "Relation projection operations")
public class RelationProjectionOperationsApiController {

    private final RelationProjectionOperationsService operationsService;
    private final WorkspaceResolver workspaceResolver;
    private final SystemRepositoryService repositoryService;
    private final RepositoryMembershipService membershipService;
    private final BranchGraphVectors vectors;
    private final HistoryIndexMaintenance maintenance;

    public RelationProjectionOperationsApiController(
            RelationProjectionOperationsService operationsService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService, BranchGraphVectors vectors, HistoryIndexMaintenance maintenance) {
        this.maintenance = maintenance;
        this.vectors = vectors;
        this.operationsService = operationsService;
        this.workspaceResolver = workspaceResolver;
        this.repositoryService = repositoryService;
        this.membershipService = membershipService;
    }

    @Operation(summary = "Inspect the selected branch vector generation",
            description = "Checks the retained node and relation index against the authorized branch, current commit, catalogue and embedding model.")
    @GetMapping("/vectors")
    public ResponseEntity<BranchGraphVectors.Status> vectorStatus() {
        var context = workspaceResolver.resolveUnambiguousRepositoryForRead();
        if (context == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        return selectedResponse(context).body(vectors.inspect(context));
    }

    @Operation(summary = "Rebuild the selected branch vector generation",
            description = "Requires a writable scope and the current commit as a strong If-Match ETag. Publishes the node and relation vectors together.")
    @PostMapping("/vectors/rebuild")
    public ResponseEntity<BranchGraphVectors.Status> rebuildVectors(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return changeVectors(ifMatch, false);
    }

    @Operation(summary = "Purge the selected branch vector generation",
            description = "Requires a writable scope and the current commit as a strong If-Match ETag. Removes the selected generation and retains the branch relation projection.")
    @org.springframework.web.bind.annotation.DeleteMapping("/vectors")
    public ResponseEntity<BranchGraphVectors.Status> purgeVectors(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return changeVectors(ifMatch, true);
    }

    private ResponseEntity<BranchGraphVectors.Status> changeVectors(String ifMatch, boolean purge) {
        var selected = workspaceResolver.resolveUnambiguousRepositoryForRead();
        var context = selected == null ? null : writableContext(selected);
        if (context == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            String commit = GitHttpPrecondition.expectedHead(ifMatch, null);
            var outcome = submit(context, purge ? DerivedProjection.Kind.VECTOR_PURGE : DerivedProjection.Kind.VECTOR_BUILD, commit);
            var receipt = outcome.receipt();
            if (!"COMPLETED".equals(receipt.phase())) return decision(receipt).build();
            if (purge) return ResponseEntity.noContent().header("X-Taxonomy-Operation-Id", receipt.operationId()).build();
            var result = outcome.result();
            return selectedResponse(context).header("X-Taxonomy-Operation-Id", receipt.operationId())
                    .eTag(GitHttpPrecondition.etag(commit)).body(new BranchGraphVectors.Status("READY", result.generation(),
                            commit, result.model(), result.nodes(), result.relations()));
        } catch (GitHttpPrecondition.PreconditionRequiredException missing) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        } catch (GitHttpPrecondition.InvalidPreconditionException invalid) {
            return ResponseEntity.badRequest().build();
        } catch (RebuildHeadConflictException moved) {
            var response = ResponseEntity.status(HttpStatus.PRECONDITION_FAILED);
            if (moved.getActualHeadCommit() != null) response.eTag(GitHttpPrecondition.etag(moved.getActualHeadCommit()));
            return response.build();
        } catch (com.taxonomy.error.SearchUnavailableException unavailable) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    private HistoryIndexMaintenance.ProjectionOutcome submit(RepositoryContext context, DerivedProjection.Kind kind, String commit) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var request = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet ? servlet.getRequest() : null;
        String key = request == null ? null : request.getHeader("Idempotency-Key");
        String cause = request == null ? null : request.getHeader("X-Causation-Id");
        return maintenance.submitProjection(context, kind, commit, key, cause);
    }

    private static ResponseEntity.BodyBuilder decision(HistoryIndexJournal.Receipt receipt) {
        HttpStatus status = switch (receipt.phase()) {
            case "DENIED" -> HttpStatus.FORBIDDEN;
            case "SUPERSEDED", "MANUAL_REVIEW" -> HttpStatus.CONFLICT;
            case "AWAITING_AUTHORIZATION" -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).header("X-Taxonomy-Operation-Id", receipt.operationId());
    }

    @GetMapping("/operations/{id}")
    public ResponseEntity<HistoryIndexJournal.Receipt> operation(@org.springframework.web.bind.annotation.PathVariable String id) {
        return ResponseEntity.ok(maintenance.projectionStatus(operationScope(), id));
    }

    @PostMapping("/operations/{id}/retry")
    public ResponseEntity<HistoryIndexJournal.Receipt> retry(@org.springframework.web.bind.annotation.PathVariable String id) {
        var receipt = maintenance.retryProjection(operationScope(), id);
        return "COMPLETED".equals(receipt.phase()) ? ResponseEntity.ok(receipt) : decision(receipt).body(receipt);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/operations/{id}")
    public ResponseEntity<HistoryIndexJournal.Receipt> archive(@org.springframework.web.bind.annotation.PathVariable String id) {
        return ResponseEntity.ok(maintenance.archiveProjection(operationScope(), id));
    }

    private RepositoryContext operationScope() {
        var selected = workspaceResolver.resolveUnambiguousRepositoryForRead();
        var writable = selected == null ? null : writableContext(selected);
        if (writable == null) throw new org.springframework.security.access.AccessDeniedException("Projection maintenance permission is unavailable");
        return writable;
    }

    private static ResponseEntity.BodyBuilder selectedResponse(RepositoryContext context) {
        return ResponseEntity.ok()
                .header("X-Taxonomy-Repository", context.repositoryId())
                .header("X-Taxonomy-Workspace-Id", java.util.Objects.toString(context.workspaceId(), ""))
                .header("X-Taxonomy-Branch", context.branch());
    }

    @Operation(summary = "Inspect exact-branch projection readiness and recovery",
            description = "Inspects relation projection/index readiness against the current Git head in a writable repository context. Returns the head ETag when present and does not trigger a rebuild.")
    @GetMapping("/readiness")
    public ResponseEntity<ProjectionOperationResponse> readiness() {
        RepositoryContext context = writableContext(
                workspaceResolver.resolveUnambiguousRepositoryForRead());
        if (context == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        ProjectionStatus status = operationsService.inspect(context);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (status.readiness().currentHeadCommit() != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(
                            status.readiness().currentHeadCommit()));
        }
        return response.body(ProjectionOperationResponse.status(
                context, status));
    }

    @Operation(summary = "Rebuild an exact branch projection from Git",
            description = "Rebuilds the database relation projection from the exact expected Git branch head in a writable repository context. Requires a head precondition and rejects source movement; rebuilding is not a new semantic relation edit.")
    @PostMapping("/rebuild")
    public ResponseEntity<ProjectionOperationResponse> rebuild(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch) {
        try {
            RepositoryContext context = writableContext(
                    workspaceResolver.resolveUnambiguousRepositoryForRead());
            if (context == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String expectedHead = GitHttpPrecondition.expectedHead(
                    ifMatch, ifNoneMatch);
            if (expectedHead == null) return ResponseEntity.badRequest().build();
            var outcome = submit(context, DerivedProjection.Kind.RELATION, expectedHead);
            var receipt = outcome.receipt();
            if (!"COMPLETED".equals(receipt.phase())) {
                var response = decision(receipt);
                var evidence = outcome.result();
                if (evidence == null) return response.build();
                if (evidence.currentCommit() != null) response.eTag(GitHttpPrecondition.etag(evidence.currentCommit()));
                String state = "RECONCILIATION_PENDING".equals(receipt.reason()) ? "RECOVERY_RECONCILIATION_PENDING" : receipt.reason();
                return response.body(new ProjectionOperationResponse(context.repositoryId(), context.workspaceId(), context.branch(),
                        state, evidence.state(), evidence.currentCommit(), evidence.projectedCommit(), evidence.relations(),
                        List.of(), null, null, null, null, null));
            }
            var result = outcome.result();
            return ResponseEntity.ok().header("X-Taxonomy-Operation-Id", receipt.operationId())
                    .eTag(GitHttpPrecondition.etag(expectedHead)).body(new ProjectionOperationResponse(
                            context.repositoryId(), context.workspaceId(), context.branch(), "REBUILT", "READY",
                            expectedHead, expectedHead, result.relations(), List.of(), result.recovered(),
                            result.superseded(), result.pending(), null, null));
        } catch (GitHttpPrecondition.PreconditionRequiredException error) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        } catch (RebuildHeadConflictException error) {
            return preconditionFailed(
                    error.getExpectedHeadCommit(),
                    error.getActualHeadCommit());
        } catch (RebuildVerificationException error) {
            return verificationFailed(error);
        } catch (RecoveryReconciliationPendingException error) {
            String commit = error.getRebuild().authoritativeCommitId();
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .header(HttpHeaders.ETAG, GitHttpPrecondition.etag(commit))
                    .body(ProjectionOperationResponse.reconciliationPending(
                            error.getRebuild().repositoryId(),
                            error.getRebuild().workspaceId(),
                            error.getRebuild().branch(),
                            commit,
                            error.getRebuild().relationCount()));
        } catch (BranchProjectionSourceException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (DerivedProjection.SourceUnavailable error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    private static ResponseEntity<ProjectionOperationResponse>
            preconditionFailed(
                    String expectedHeadCommit,
                    String actualHeadCommit) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                HttpStatus.PRECONDITION_FAILED);
        if (actualHeadCommit != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(actualHeadCommit));
        }
        return response.body(ProjectionOperationResponse.conflict(
                expectedHeadCommit, actualHeadCommit));
    }

    private static ResponseEntity<ProjectionOperationResponse>
            verificationFailed(RebuildVerificationException error) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(
                HttpStatus.CONFLICT);
        String currentHead = error.getReadiness().currentHeadCommit();
        if (currentHead != null) {
            response.header(
                    HttpHeaders.ETAG,
                    GitHttpPrecondition.etag(currentHead));
        }
        return response.body(
                ProjectionOperationResponse.verificationFailed(error));
    }

    /** Central projection operations require repository-maintainer authority. */
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

    private static boolean isApplicationAdmin() {
        Authentication authentication = SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(
                        authority.getAuthority()));
    }

    public record RecoveryResponse(
            Long id,
            String authoritativeCommitId,
            String previousHeadCommit,
            String causationId,
            String status,
            int attemptCount,
            String failureType,
            String failureMessage,
            Instant firstObservedAt,
            Instant lastObservedAt) {

        static RecoveryResponse from(RecoveryRecord recovery) {
            return new RecoveryResponse(
                    recovery.id(),
                    recovery.authoritativeCommitId(),
                    recovery.previousHeadCommit(),
                    recovery.causationId(),
                    recovery.status().name(),
                    recovery.attemptCount(),
                    recovery.failureType(),
                    recovery.failureMessage(),
                    recovery.firstObservedAt(),
                    recovery.lastObservedAt());
        }
    }

    public record ProjectionOperationResponse(
            String repositoryId,
            String workspaceId,
            String branch,
            String operationStatus,
            String readinessState,
            String currentHeadCommit,
            String projectedCommit,
            int relationCount,
            List<RecoveryResponse> pendingRecoveries,
            Integer recoveredCount,
            Integer supersededCount,
            Integer remainingPendingCount,
            String expectedHeadCommit,
            String actualHeadCommit) {

        public ProjectionOperationResponse {
            pendingRecoveries = pendingRecoveries == null
                    ? List.of()
                    : List.copyOf(pendingRecoveries);
        }

        static ProjectionOperationResponse status(
                RepositoryContext context,
                ProjectionStatus status) {
            return new ProjectionOperationResponse(
                    context.repositoryId(),
                    context.workspaceId(),
                    context.branch(),
                    "INSPECTED",
                    status.readiness().state().name(),
                    status.readiness().currentHeadCommit(),
                    status.readiness().projectedCommit(),
                    status.readiness().rows().size(),
                    status.pendingRecoveries().stream()
                            .map(RecoveryResponse::from)
                            .toList(),
                    null,
                    null,
                    status.pendingRecoveries().size(),
                    null,
                    null);
        }

        static ProjectionOperationResponse verificationFailed(
                RebuildVerificationException error) {
            return new ProjectionOperationResponse(
                    error.getRebuild().repositoryId(),
                    error.getRebuild().workspaceId(),
                    error.getRebuild().branch(),
                    "VERIFICATION_FAILED",
                    error.getReadiness().state().name(),
                    error.getReadiness().currentHeadCommit(),
                    error.getReadiness().projectedCommit(),
                    error.getRebuild().relationCount(),
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static ProjectionOperationResponse reconciliationPending(
                String repositoryId,
                String workspaceId,
                String branch,
                String commit,
                int relationCount) {
            return new ProjectionOperationResponse(
                    repositoryId,
                    workspaceId,
                    branch,
                    "RECOVERY_RECONCILIATION_PENDING",
                    null,
                    commit,
                    commit,
                    relationCount,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        static ProjectionOperationResponse conflict(
                String expectedHeadCommit,
                String actualHeadCommit) {
            return new ProjectionOperationResponse(
                    null,
                    null,
                    null,
                    "PRECONDITION_FAILED",
                    null,
                    actualHeadCommit,
                    null,
                    0,
                    List.of(),
                    null,
                    null,
                    null,
                    expectedHeadCommit,
                    actualHeadCommit);
        }
    }
}
