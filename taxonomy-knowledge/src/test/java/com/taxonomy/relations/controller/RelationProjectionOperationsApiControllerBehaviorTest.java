package com.taxonomy.relations.controller;

import com.taxonomy.relations.controller.RelationProjectionOperationsApiController.ProjectionOperationResponse;
import com.taxonomy.relations.controller.RelationProjectionOperationsApiController.RecoveryResponse;
import com.taxonomy.relations.model.RelationProjectionRecovery.RecoveryStatus;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionSourceException;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationProjectionOperationsService;
import com.taxonomy.relations.service.RelationProjectionOperationsService.ProjectionStatus;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildHeadConflictException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildOperation;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildVerificationException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RecoveryReconciliationPendingException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.ReconciliationResult;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.RecoveryRecord;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RelationProjectionOperationsApiControllerBehaviorTest {

    private static final String HEAD = "a".repeat(40);
    private static final String PREVIOUS = "b".repeat(40);
    private static final String ETAG = '"' + HEAD + '"';
    private static final RepositoryContext WORKSPACE = RepositoryContext.workspace(
            "repo-selected", "workspace-selected", "review/exact", "alice");

    @Mock private RelationProjectionOperationsService operationsService;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private SystemRepositoryService repositoryService;
    @Mock private RepositoryMembershipService membershipService;

    private RelationProjectionOperationsApiController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        controller = new RelationProjectionOperationsApiController(
                operationsService, workspaceResolver, repositoryService,
                membershipService);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readinessReturnsTheSelectedWorkspaceAndCompleteRecoveryMetadata() {
        select(WORKSPACE);
        Instant first = Instant.parse("2026-01-02T03:04:05Z");
        Instant last = first.plusSeconds(60);
        RecoveryRecord recovery = new RecoveryRecord(
                17L, "repo-selected", "workspace-selected", "review/exact",
                PREVIOUS, HEAD, "review-17", RecoveryStatus.PENDING, 3,
                "ProjectionCompletionException", "Projection unavailable",
                first, last, null);
        when(operationsService.inspect(WORKSPACE)).thenReturn(new ProjectionStatus(
                new Readiness(ReadinessState.STALE, HEAD, PREVIOUS, List.of()),
                List.of(recovery)));

        var response = controller.readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "repo-selected", "workspace-selected", "review/exact",
                "INSPECTED", "STALE", HEAD, PREVIOUS, 0,
                List.of(new RecoveryResponse(
                        17L, HEAD, PREVIOUS, "review-17", "PENDING", 3,
                        "ProjectionCompletionException", "Projection unavailable",
                        first, last)),
                null, null, 1, null, null));
        verify(operationsService).inspect(same(WORKSPACE));
        verifyNoMoreInteractions(operationsService);
        verifyNoInteractions(repositoryService, membershipService);
    }

    @Test
    void readinessPreservesForkScopeAndOmitsTheEtagWhenTheBranchIsAbsent() {
        RepositoryContext fork = new RepositoryContext(
                "fork-selected", null, "review/fork", "alice", RepositoryScope.FORK);
        select(fork);
        when(operationsService.inspect(fork)).thenReturn(new ProjectionStatus(
                new Readiness(ReadinessState.BRANCH_MISSING, null, null, List.of()),
                List.of()));

        var response = controller.readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isNull();
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "fork-selected", null, "review/fork", "INSPECTED", "BRANCH_MISSING",
                null, null, 0, List.of(), null, null, 0, null, null));
        verify(operationsService).inspect(same(fork));
        verifyNoMoreInteractions(operationsService);
        verifyNoInteractions(repositoryService, membershipService);
    }

    @Test
    void repositoryMaintainerCanInspectTheSelectedCentralBranch() {
        RepositoryContext central = RepositoryContext.centralRead(
                "central-selected", "review/central", "alice");
        RepositoryContext writable = RepositoryContext.centralWrite(
                "central-selected", "review/central", "alice");
        select(central);
        authenticate("ROLE_USER");
        SystemRepository repository = mock(SystemRepository.class);
        when(repositoryService.getRepository("central-selected")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "alice")).thenReturn(true);
        when(operationsService.inspect(writable)).thenReturn(new ProjectionStatus(
                ready(), List.of()));

        var response = controller.readiness();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "central-selected", null, "review/central", "INSPECTED", "READY",
                HEAD, HEAD, 0, List.of(), null, null, 0, null, null));
        verify(repositoryService).getRepository("central-selected");
        verify(membershipService).canMaintain(same(repository), eq("alice"));
        verify(operationsService).inspect(writable);
        verifyNoMoreInteractions(operationsService, repositoryService, membershipService);
    }

    @Test
    void applicationAdminCanRebuildTheSelectedCentralBranchWithoutMembership() throws Exception {
        RepositoryContext central = RepositoryContext.centralRead(
                "central-selected", "review/central", "alice");
        RepositoryContext writable = RepositoryContext.centralWrite(
                "central-selected", "review/central", "alice");
        select(central);
        authenticate("ROLE_USER", "ROLE_ADMIN");
        when(repositoryService.getRepository("central-selected"))
                .thenReturn(mock(SystemRepository.class));
        when(operationsService.rebuild(writable, HEAD)).thenReturn(rebuilt(writable));

        var response = controller.rebuild("  " + ETAG.toUpperCase() + "  ", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "central-selected", null, "review/central", "REBUILT", "READY",
                HEAD, HEAD, 0, List.of(), 2, 1, 0, null, null));
        verify(repositoryService).getRepository("central-selected");
        verify(operationsService).rebuild(writable, HEAD);
        verifyNoMoreInteractions(operationsService, repositoryService);
        verifyNoInteractions(membershipService);
    }

    @ParameterizedTest
    @CsvSource({"CENTRAL_READ, false", "CENTRAL_WRITE, true"})
    void centralInspectionAndRebuildRequireAuthorityEvenBeforePreconditionParsing(
            RepositoryScope scope, boolean authenticated) {
        select(new RepositoryContext("central-selected", null, "review/central", "alice", scope));
        if (authenticated) {
            authenticate("ROLE_USER");
        }
        SystemRepository repository = mock(SystemRepository.class);
        when(repositoryService.getRepository("central-selected")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "alice")).thenReturn(false);

        assertEmpty(controller.readiness(), HttpStatus.FORBIDDEN);
        assertEmpty(controller.rebuild(null, null), HttpStatus.FORBIDDEN);

        verifyNoInteractions(operationsService);
        verify(repositoryService, times(2)).getRepository("central-selected");
        verify(membershipService, times(2)).canMaintain(repository, "alice");
        verifyNoMoreInteractions(repositoryService, membershipService);
    }

    @Test
    void workspaceRebuildReturnsReconciliationCountsWithoutChangingItsContext() throws Exception {
        select(WORKSPACE);
        when(operationsService.rebuild(WORKSPACE, HEAD)).thenReturn(rebuilt(WORKSPACE));

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "repo-selected", "workspace-selected", "review/exact", "REBUILT", "READY",
                HEAD, HEAD, 0, List.of(), 2, 1, 0, null, null));
        verify(operationsService).rebuild(same(WORKSPACE), eq(HEAD));
        verifyNoMoreInteractions(operationsService);
        verifyNoInteractions(repositoryService, membershipService);
    }

    @Test
    void rebuildWithoutAPreconditionDoesNotInvokeTheOperationsService() {
        select(WORKSPACE);

        assertEmpty(controller.rebuild(null, null), HttpStatus.PRECONDITION_REQUIRED);

        verifyNoInteractions(operationsService, repositoryService, membershipService);
    }

    @Test
    void rebuildWithConflictingPreconditionsDoesNotInvokeTheOperationsService() {
        select(WORKSPACE);

        assertEmpty(controller.rebuild(ETAG, "*"), HttpStatus.BAD_REQUEST);

        verifyNoInteractions(operationsService, repositoryService, membershipService);
    }

    @ParameterizedTest
    @MethodSource("headConflicts")
    void staleRebuildReturnsBothHeadsAndOnlyAnExistingCurrentHeadEtag(
            Exception failure, String actual) throws Exception {
        select(WORKSPACE);
        when(operationsService.rebuild(WORKSPACE, HEAD)).thenThrow(failure);

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(response.getHeaders().getETag())
                .isEqualTo(actual == null ? null : '"' + actual + '"');
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                null, null, null, "PRECONDITION_FAILED", null, actual, null,
                0, List.of(), null, null, null, HEAD, actual));
        verify(operationsService).rebuild(WORKSPACE, HEAD);
        verifyNoMoreInteractions(operationsService);
    }

    @ParameterizedTest
    @MethodSource("unverifiedProjections")
    void anUnverifiedRebuildReportsItsContextAndReadinessInsteadOfSuccess(
            Readiness readiness) throws Exception {
        select(WORKSPACE);
        RebuildResult rebuild = new RebuildResult(
                "repo-selected", "workspace-selected", "review/exact", HEAD, 2);
        when(operationsService.rebuild(WORKSPACE, HEAD))
                .thenThrow(new RebuildVerificationException(rebuild, readiness));

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getETag())
                .isEqualTo(readiness.currentHeadCommit() == null ? null : ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "repo-selected", "workspace-selected", "review/exact", "VERIFICATION_FAILED",
                readiness.state().name(), readiness.currentHeadCommit(), readiness.projectedCommit(),
                2, List.of(), null, null, null, null, null));
        verify(operationsService).rebuild(WORKSPACE, HEAD);
        verifyNoMoreInteractions(operationsService);
    }

    @Test
    void rebuiltProjectionWithPendingRecoveryReconciliationReturnsAccepted() throws Exception {
        select(WORKSPACE);
        RebuildResult rebuild = new RebuildResult(
                "repo-selected", "workspace-selected", "review/exact", HEAD, 2);
        when(operationsService.rebuild(WORKSPACE, HEAD)).thenThrow(
                new RecoveryReconciliationPendingException(
                        rebuild, new IllegalStateException("Recovery store unavailable")));

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "repo-selected", "workspace-selected", "review/exact",
                "RECOVERY_RECONCILIATION_PENDING", null, HEAD, HEAD, 2,
                List.of(), null, null, null, null, null));
        verify(operationsService).rebuild(WORKSPACE, HEAD);
        verifyNoMoreInteractions(operationsService);
    }

    @ParameterizedTest
    @MethodSource("rebuildFailures")
    void rebuildFailuresHaveStableStatusCodesAndDoNotRetry(
            Exception failure, HttpStatus status) throws Exception {
        select(WORKSPACE);
        when(operationsService.rebuild(WORKSPACE, HEAD)).thenThrow(failure);

        assertEmpty(controller.rebuild(ETAG, null), status);

        verify(operationsService).rebuild(WORKSPACE, HEAD);
        verifyNoMoreInteractions(operationsService);
        verifyNoInteractions(repositoryService, membershipService);
    }

    private static Stream<Arguments> headConflicts() {
        return Stream.of(
                Arguments.of(new BranchHeadConflictException(
                        "review/exact", HEAD, PREVIOUS, "Branch advanced"), PREVIOUS),
                Arguments.of(new RebuildHeadConflictException(HEAD, null), null));
    }

    private static Stream<Readiness> unverifiedProjections() {
        return Stream.of(
                new Readiness(ReadinessState.CORRUPT, HEAD, HEAD, List.of()),
                new Readiness(ReadinessState.BRANCH_MISSING, null, null, List.of()));
    }

    private static Stream<Arguments> rebuildFailures() {
        return Stream.of(
                Arguments.of(new BranchProjectionSourceException("Missing Git source"), HttpStatus.CONFLICT),
                Arguments.of(new IllegalArgumentException("Invalid branch context"), HttpStatus.BAD_REQUEST),
                Arguments.of(new IllegalStateException("Projection storage conflict"), HttpStatus.CONFLICT),
                Arguments.of(new IOException("Git storage unavailable"), HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static Readiness ready() {
        return new Readiness(ReadinessState.READY, HEAD, HEAD, List.of());
    }

    private static RebuildOperation rebuilt(RepositoryContext context) {
        return new RebuildOperation(
                new RebuildResult(context.repositoryId(), context.workspaceId(),
                        context.branch(), HEAD, 0),
                new ReconciliationResult(HEAD, 2, 1, 0), ready());
    }

    private void select(RepositoryContext context) {
        when(workspaceResolver.resolveCurrentRepositoryContext()).thenReturn(context);
    }

    private static void authenticate(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "alice", null, AuthorityUtils.createAuthorityList(roles)));
    }

    private static void assertEmpty(ResponseEntity<?> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getETag()).isNull();
        assertThat(response.getBody()).isNull();
    }
}
