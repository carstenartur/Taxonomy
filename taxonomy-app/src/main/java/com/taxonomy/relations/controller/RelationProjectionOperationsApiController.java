package com.taxonomy.relations.controller;

import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionSourceException;
import com.taxonomy.relations.service.RelationProjectionOperationsService;
import com.taxonomy.relations.service.RelationProjectionOperationsService.ProjectionStatus;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildHeadConflictException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildOperation;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RecoveryReconciliationPendingException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.RecoveryRecord;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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

    public RelationProjectionOperationsApiController(
            RelationProjectionOperationsService operationsService,
            WorkspaceResolver workspaceResolver,
            SystemRepositoryService repositoryService,
            RepositoryMembershipService membershipService) {
        this.operationsService = operationsService;
        this.workspaceResolver = workspaceResolver;
        this.repositoryService = repositoryService;
        this.membershipService = membershipService;
    }

    @Operation(summary = "Inspect exact-branch projection readiness and recovery")
    @GetMapping("/readiness")
    public ResponseEntity<ProjectionOperationResponse> readiness() {
        RepositoryContext context = writableContext(
                workspaceResolver.resolveCurrentRepositoryContext());
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

    @Operation(summary = "Rebuild an exact branch projection from Git")
    @PostMapping("/rebuild")
    public ResponseEntity<ProjectionOperationResponse> rebuild(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
            String ifNoneMatch) {
        try {
            RepositoryContext context = writableContext(
                    workspaceResolver.resolveCurrentRepositoryContext());
            if (context == null) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String expectedHead = GitHttpPrecondition.expectedHead(
                    ifMatch, ifNoneMatch);
            RebuildOperation operation = operationsService.rebuild(
                    context, expectedHead);
            return ResponseEntity.ok()
                    .header(
                            HttpHeaders.ETAG,
                            GitHttpPrecondition.etag(
                                    operation.rebuild()
                                            .authoritativeCommitId()))
                    .body(ProjectionOperationResponse.rebuilt(
                            context, operation));
        } catch (GitHttpPrecondition.PreconditionRequiredException error) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED).build();
        } catch (BranchHeadConflictException error) {
            return preconditionFailed(
                    error.getExpectedHeadCommit(),
                    error.getActualHeadCommit());
        } catch (RebuildHeadConflictException error) {
            return preconditionFailed(
                    error.getExpectedHeadCommit(),
                    error.getActualHeadCommit());
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
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IOException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
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

        static ProjectionOperationResponse rebuilt(
                RepositoryContext context,
                RebuildOperation operation) {
            return new ProjectionOperationResponse(
                    context.repositoryId(),
                    context.workspaceId(),
                    context.branch(),
                    "REBUILT",
                    operation.readiness().state().name(),
                    operation.readiness().currentHeadCommit(),
                    operation.readiness().projectedCommit(),
                    operation.rebuild().relationCount(),
                    List.of(),
                    operation.reconciliation().recoveredCount(),
                    operation.reconciliation().supersededCount(),
                    operation.reconciliation().remainingPendingCount(),
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
