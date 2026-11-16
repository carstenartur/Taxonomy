package com.taxonomy.relations.controller;

import com.taxonomy.versioning.service.HistoryIndexJournal;
import com.taxonomy.versioning.service.HistoryIndexMaintenance;
import com.taxonomy.workspace.service.DerivedProjection;
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
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildVerificationException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RecoveryReconciliationPendingException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.RecoveryRecord;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.model.UserWorkspace;
import com.taxonomy.workspace.model.WorkspaceProvisioningStatus;
import com.taxonomy.workspace.repository.UserWorkspaceRepository;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import com.taxonomy.workspace.service.WorkspaceManager;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @Mock private HistoryIndexMaintenance maintenance;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private SystemRepositoryService repositoryService;
    @Mock private RepositoryMembershipService membershipService;
    @Mock private com.taxonomy.relations.service.BranchGraphVectors vectors;

    private RelationProjectionOperationsApiController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        controller = new RelationProjectionOperationsApiController(
                operationsService, workspaceResolver, repositoryService,
                membershipService, vectors, maintenance);
    }

    @Test
    void vectorOperationsUseOnlyAnExistingSelectedWorkingCopyAndStrongHead() {
        when(workspaceResolver.resolveUnambiguousRepositoryForRead()).thenReturn(WORKSPACE);
        var ready = new com.taxonomy.relations.service.BranchGraphVectors.Status("READY", "generation", HEAD, "model", 2, 1);
        when(vectors.inspect(WORKSPACE)).thenReturn(ready);
        var result = new DerivedProjection.Result("generation", "model", 2, 1, null, null, null);
        when(maintenance.submitProjection(WORKSPACE, DerivedProjection.Kind.VECTOR_BUILD, HEAD, null, null))
                .thenReturn(completed(WORKSPACE, result));
        when(maintenance.submitProjection(WORKSPACE, DerivedProjection.Kind.VECTOR_PURGE, HEAD, null, null))
                .thenReturn(completed(WORKSPACE, new DerivedProjection.Result(null, null, 0, 0, null, null, null)));
        var inspected = controller.vectorStatus();
        assertThat(inspected.getBody()).isEqualTo(ready);
        assertThat(inspected.getHeaders().getFirst("X-Taxonomy-Repository")).isEqualTo(WORKSPACE.repositoryId());
        assertThat(inspected.getHeaders().getFirst("X-Taxonomy-Workspace-Id")).isEqualTo(WORKSPACE.workspaceId());
        assertThat(inspected.getHeaders().getFirst("X-Taxonomy-Branch")).isEqualTo(WORKSPACE.branch());
        assertThat(controller.rebuildVectors(null).getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        assertThat(controller.rebuildVectors(ETAG).getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(controller.purgeVectors(ETAG).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(maintenance).submitProjection(WORKSPACE, DerivedProjection.Kind.VECTOR_PURGE, HEAD, null, null);
        verify(workspaceResolver, never()).resolveCurrentRepositoryContext();
    }

    @Test
    void aChangedOrAmbiguousSelectionStopsEveryProjectionOperationBeforeStorage() {
        var changed = new ResponseStatusException(HttpStatus.CONFLICT);
        when(workspaceResolver.resolveUnambiguousRepositoryForRead()).thenThrow(changed);
        assertThatThrownBy(controller::readiness).isSameAs(changed);
        assertThatThrownBy(controller::vectorStatus).isSameAs(changed);
        assertThatThrownBy(() -> controller.rebuild(ETAG, null)).isSameAs(changed);
        assertThatThrownBy(() -> controller.rebuildVectors(ETAG)).isSameAs(changed);
        assertThatThrownBy(() -> controller.purgeVectors(ETAG)).isSameAs(changed);
        verifyNoInteractions(maintenance, operationsService, vectors, repositoryService, membershipService);
    }

    @Test
    void ambiguousCentralInspectionAndMovedWorkspaceRebuildCannotReachEitherProjection() {
        var primary = new SystemRepository();
        primary.setRepositoryId("primary");
        primary.setDefaultBranch("main");
        var second = new SystemRepository();
        second.setRepositoryId("another");
        var manager = mock(WorkspaceManager.class);
        var resolver = new WorkspaceResolver(new WorkspaceContextResolver(
                manager, repositoryService, mock(UserWorkspaceRepository.class), membershipService));
        var endpoint = new RelationProjectionOperationsApiController(
                operationsService, resolver, repositoryService, membershipService, vectors, maintenance);
        authenticate("ROLE_ADMIN");
        when(repositoryService.getPrimaryRepository()).thenReturn(primary);
        when(repositoryService.listActiveRepositories()).thenReturn(List.of(primary, second));
        when(membershipService.canRead(second, "alice")).thenReturn(true);
        var central = new MockHttpServletRequest();
        central.addParameter("workspaceId", "");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(central));
        try {
            assertThatThrownBy(endpoint::readiness).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(manager);
            var copy = new UserWorkspace();
            copy.setUsername("alice");
            copy.setWorkspaceId("copy");
            copy.setSourceRepositoryId("primary");
            copy.setCurrentBranch("now-selected");
            copy.setProvisioningStatus(WorkspaceProvisioningStatus.READY);
            when(manager.getWorkspaceById("copy")).thenReturn(copy);
            when(repositoryService.getRepository("primary")).thenReturn(primary);
            var moved = new MockHttpServletRequest();
            moved.addParameter("repositoryId", "primary");
            moved.addParameter("workspaceId", "copy");
            moved.addParameter("branch", "previously-selected");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(moved));
            assertThatThrownBy(() -> endpoint.rebuild(ETAG, null)).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
            verifyNoInteractions(maintenance, operationsService, vectors);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
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
        verifyNoMoreInteractions(operationsService, maintenance);
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
        verifyNoMoreInteractions(operationsService, maintenance);
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
        when(maintenance.submitProjection(writable, DerivedProjection.Kind.RELATION, HEAD, null, null))
                .thenReturn(completed(writable, new DerivedProjection.Result(null, null, 0, 0, 2, 1, 0)));

        var response = controller.rebuild("  " + ETAG.toUpperCase() + "  ", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "central-selected", null, "review/central", "REBUILT", "READY",
                HEAD, HEAD, 0, List.of(), 2, 1, 0, null, null));
        verify(repositoryService).getRepository("central-selected");
        verify(maintenance).submitProjection(writable, DerivedProjection.Kind.RELATION, HEAD, null, null);
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
        when(maintenance.submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null)).thenReturn(completed(WORKSPACE, new DerivedProjection.Result(null, null, 0, 0, 2, 1, 0)));

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "repo-selected", "workspace-selected", "review/exact", "REBUILT", "READY",
                HEAD, HEAD, 0, List.of(), 2, 1, 0, null, null));
        verify(maintenance).submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null);
        verifyNoMoreInteractions(operationsService, maintenance);
        verifyNoInteractions(repositoryService, membershipService);
    }

    @Test
    void rebuildWithoutAPreconditionDoesNotInvokeTheOperationsService() {
        select(WORKSPACE);

        assertEmpty(controller.rebuild(null, null), HttpStatus.PRECONDITION_REQUIRED);

        verifyNoInteractions(maintenance, operationsService, repositoryService, membershipService);
    }

    @Test
    void rebuildWithConflictingPreconditionsDoesNotInvokeTheOperationsService() {
        select(WORKSPACE);

        assertEmpty(controller.rebuild(ETAG, "*"), HttpStatus.BAD_REQUEST);
        assertEmpty(controller.rebuild(null, "*"), HttpStatus.BAD_REQUEST);

        verifyNoInteractions(maintenance, operationsService, repositoryService, membershipService);
    }

    @ParameterizedTest
    @MethodSource("headConflicts")
    void staleRebuildReturnsBothHeadsAndOnlyAnExistingCurrentHeadEtag(
            Exception failure, String actual) throws Exception {
        select(WORKSPACE);
        when(maintenance.submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null)).thenThrow(failure);

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(response.getHeaders().getETag())
                .isEqualTo(actual == null ? null : '"' + actual + '"');
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                null, null, null, "PRECONDITION_FAILED", null, actual, null,
                0, List.of(), null, null, null, HEAD, actual));
        verify(maintenance).submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null);
        verifyNoMoreInteractions(operationsService, maintenance);
    }

    @ParameterizedTest
    @MethodSource("unverifiedProjections")
    void anUnverifiedRebuildReportsItsContextAndReadinessInsteadOfSuccess(
            Readiness readiness) throws Exception {
        select(WORKSPACE);
        RebuildResult rebuild = new RebuildResult(
                "repo-selected", "workspace-selected", "review/exact", HEAD, 2);
        when(maintenance.submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null))
                .thenThrow(new RebuildVerificationException(rebuild, readiness));

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getETag())
                .isEqualTo(readiness.currentHeadCommit() == null ? null : ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "repo-selected", "workspace-selected", "review/exact", "VERIFICATION_FAILED",
                readiness.state().name(), readiness.currentHeadCommit(), readiness.projectedCommit(),
                2, List.of(), null, null, null, null, null));
        verify(maintenance).submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null);
        verifyNoMoreInteractions(operationsService, maintenance);
    }

    @Test
    void rebuiltProjectionWithPendingRecoveryReconciliationReturnsAccepted() throws Exception {
        select(WORKSPACE);
        RebuildResult rebuild = new RebuildResult(
                "repo-selected", "workspace-selected", "review/exact", HEAD, 2);
        when(maintenance.submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null)).thenThrow(
                new RecoveryReconciliationPendingException(
                        rebuild, new IllegalStateException("Recovery store unavailable")));

        var response = controller.rebuild(ETAG, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ProjectionOperationResponse(
                "repo-selected", "workspace-selected", "review/exact",
                "RECOVERY_RECONCILIATION_PENDING", null, HEAD, HEAD, 2,
                List.of(), null, null, null, null, null));
        verify(maintenance).submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null);
        verifyNoMoreInteractions(operationsService, maintenance);
    }

    @ParameterizedTest
    @MethodSource("rebuildFailures")
    void rebuildFailuresHaveStableStatusCodesAndDoNotRetry(
            Exception failure, HttpStatus status) throws Exception {
        select(WORKSPACE);
        when(maintenance.submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null)).thenThrow(failure);

        assertEmpty(controller.rebuild(ETAG, null), status);

        verify(maintenance).submitProjection(WORKSPACE, DerivedProjection.Kind.RELATION, HEAD, null, null);
        verifyNoMoreInteractions(operationsService, maintenance);
        verifyNoInteractions(repositoryService, membershipService);
    }

    private static Stream<Arguments> headConflicts() {
        return Stream.of(
                Arguments.of(new RebuildHeadConflictException(HEAD, PREVIOUS), PREVIOUS),
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
                Arguments.of(new DerivedProjection.SourceUnavailable(new IOException("Git storage unavailable")), HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static Readiness ready() {
        return new Readiness(ReadinessState.READY, HEAD, HEAD, List.of());
    }

    @Test void scopedOperationStatusRetryAndArchivePreserveTheReceiptAndTerminalDecision() {
        select(WORKSPACE);
        var receipt = completed(WORKSPACE, new DerivedProjection.Result(null, null, 0, 1, null, null, null)).receipt();
        when(maintenance.projectionStatus(WORKSPACE, "id")).thenReturn(receipt);
        when(maintenance.retryProjection(WORKSPACE, "id")).thenReturn(receipt);
        when(maintenance.archiveProjection(WORKSPACE, "id")).thenReturn(receipt);
        assertThat(controller.operation("id").getBody()).isEqualTo(receipt);
        assertThat(controller.retry("id").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.archive("id").getBody()).isEqualTo(receipt);
        for (var state : List.of("DENIED","AWAITING_AUTHORIZATION","MANUAL_REVIEW","SUPERSEDED","RETRYABLE")) {
            var pending = new HistoryIndexJournal.Receipt("id","cause",HistoryIndexJournal.Kind.RELATION,
                    WORKSPACE.repositoryId(),WORKSPACE.workspaceId(),WORKSPACE.branch(),HEAD,"owner",state,1,null,"executor","reason",1L,2L,null,null);
            when(maintenance.retryProjection(WORKSPACE,"id")).thenReturn(pending);
            var response = controller.retry("id");
            assertThat(response.getBody()).isEqualTo(pending);
            assertThat(response.getStatusCode()).isEqualTo(switch (state) {
                case "DENIED", "AWAITING_AUTHORIZATION" -> HttpStatus.FORBIDDEN;
                case "MANUAL_REVIEW", "SUPERSEDED" -> HttpStatus.CONFLICT;
                default -> HttpStatus.ACCEPTED;
            });
        }
    }

    private static HistoryIndexMaintenance.ProjectionOutcome completed(RepositoryContext context, DerivedProjection.Result result) {
        var receipt = new HistoryIndexJournal.Receipt("retained-operation", "cause", HistoryIndexJournal.Kind.RELATION,
                context.repositoryId(), context.workspaceId(), context.branch(), HEAD, "verified", "COMPLETED",
                1, result.relations(), "verified", "APPLIED", 1L, 2L, 3L, null);
        return new HistoryIndexMaintenance.ProjectionOutcome(receipt, result);
    }

    private void select(RepositoryContext context) {
        when(workspaceResolver.resolveUnambiguousRepositoryForRead()).thenReturn(context);
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
