package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.ReadOnlyRepositoryContextException;
import com.taxonomy.relations.controller.GitProposalReviewApiController.ReviewBody;
import com.taxonomy.relations.controller.GitProposalReviewApiController.ReviewResponse;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.PendingPhase;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewPendingException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewAction;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ReviewResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
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
class GitProposalReviewApiControllerContractTest {

    private static final String HEAD = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);
    private static final String ETAG = '"' + HEAD + '"';
    private static final String KEY = "proposal-review-17";
    private static final CommandMetadata NO_RATIONALE = new CommandMetadata(KEY);
    private static final RepositoryContext WORKSPACE = RepositoryContext.workspace(
            "repo-selected", "workspace-selected", "review/exact", "alice");

    @Mock private GitAuthoritativeProposalReviewService reviewService;
    @Mock private WorkspaceResolver workspaceResolver;
    @Mock private SystemRepositoryService repositoryService;
    @Mock private RepositoryMembershipService membershipService;

    private GitProposalReviewApiController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        controller = new GitProposalReviewApiController(
                reviewService, workspaceResolver, repositoryService, membershipService);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptPreservesWorkspaceAndForwardsNormalizedPreconditionAndReviewMetadata() throws Exception {
        select(WORKSPACE);
        CommandMetadata metadata = new CommandMetadata(KEY, "Approved based on evidence");
        when(reviewService.accept(17L, WORKSPACE, HEAD, metadata)).thenReturn(
                new ReviewResult(17L, ReviewAction.ACCEPT, ProposalStatus.ACCEPTED,
                        mutation(WORKSPACE, true)));

        var response = controller.accept(
                17L, "  " + ETAG.toUpperCase() + "  ", null, "  " + KEY + "  ",
                new ReviewBody("  Approved\n based on evidence  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + AUTHORITY + '"');
        assertThat(response.getBody()).isEqualTo(new ReviewResponse(
                17L, "ACCEPT", "ACCEPTED", AUTHORITY, "UPDATED", true,
                "PROJECTED", "UPDATED", true, null, null, null));
        verify(reviewService).accept(eq(17L), same(WORKSPACE), eq(HEAD), eq(metadata));
        verifyNoMoreInteractions(reviewService);
        verifyNoInteractions(repositoryService, membershipService);
    }

    @Test
    void maintainerCanRejectInTheExactSelectedCentralRepositoryWithoutARationale() throws Exception {
        RepositoryContext central = RepositoryContext.centralRead(
                "central-selected", "review/central", "alice");
        RepositoryContext writable = RepositoryContext.centralWrite(
                "central-selected", "review/central", "alice");
        select(central);
        SystemRepository repository = mock(SystemRepository.class);
        when(repositoryService.getRepository("central-selected")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "alice")).thenReturn(true);
        when(reviewService.reject(17L, writable, HEAD, NO_RATIONALE)).thenReturn(
                new ReviewResult(17L, ReviewAction.REJECT, ProposalStatus.REJECTED,
                        mutation(writable, false)));

        var response = controller.reject(17L, ETAG, null, KEY, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + AUTHORITY + '"');
        assertThat(response.getBody()).isEqualTo(new ReviewResponse(
                17L, "REJECT", "REJECTED", AUTHORITY, "UPDATED", true,
                "PROJECTED", "UPDATED", false, null, null, null));
        verify(repositoryService).getRepository("central-selected");
        verify(membershipService).canMaintain(same(repository), eq("alice"));
        verify(reviewService).reject(17L, writable, HEAD, NO_RATIONALE);
        verifyNoMoreInteractions(reviewService, repositoryService, membershipService);
    }

    @Test
    void adminCanRevertCentrallyAndTheResponsePreservesASemanticNoOp() throws Exception {
        RepositoryContext central = RepositoryContext.centralRead(
                "central-selected", "review/central", "alice");
        RepositoryContext writable = RepositoryContext.centralWrite(
                "central-selected", "review/central", "alice");
        select(central);
        authenticate("ROLE_USER", "ROLE_ADMIN");
        when(repositoryService.getRepository("central-selected"))
                .thenReturn(mock(SystemRepository.class));
        MutationResult unchanged = new MutationResult(
                new CommandResult("central-selected", null, "review/central", writable.scope(),
                        HEAD, HEAD, ChangeKind.UNCHANGED, false, KEY),
                new ProjectionResult(ProjectionOutcome.REPLAYED, HEAD, false));
        when(reviewService.revert(17L, writable, HEAD, NO_RATIONALE)).thenReturn(
                new ReviewResult(17L, ReviewAction.REVERT, ProposalStatus.PENDING, unchanged));

        var response = controller.revert(17L, ETAG, null, KEY, new ReviewBody("  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag()).isEqualTo(ETAG);
        assertThat(response.getBody()).isEqualTo(new ReviewResponse(
                17L, "REVERT", "PENDING", HEAD, "UNCHANGED", false,
                "PROJECTED", "REPLAYED", false, null, null, null));
        verify(repositoryService).getRepository("central-selected");
        verify(reviewService).revert(17L, writable, HEAD, NO_RATIONALE);
        verifyNoMoreInteractions(reviewService, repositoryService);
        verifyNoInteractions(membershipService);
    }

    @Test
    void centralMemberCannotPerformAnyReviewEvenWhenPreconditionsAreMissing() {
        select(RepositoryContext.centralRead("central-selected", "review/central", "alice"));
        authenticate("ROLE_USER");
        SystemRepository repository = mock(SystemRepository.class);
        when(repositoryService.getRepository("central-selected")).thenReturn(repository);
        when(membershipService.canMaintain(repository, "alice")).thenReturn(false);

        assertEmpty(controller.accept(17L, null, null, KEY, null), HttpStatus.FORBIDDEN);
        assertEmpty(controller.reject(17L, null, null, KEY, null), HttpStatus.FORBIDDEN);
        assertEmpty(controller.revert(17L, null, null, KEY, null), HttpStatus.FORBIDDEN);

        verifyNoInteractions(reviewService);
        verify(repositoryService, times(3)).getRepository("central-selected");
        verify(membershipService, times(3)).canMaintain(repository, "alice");
        verifyNoMoreInteractions(repositoryService, membershipService);
    }

    @ParameterizedTest
    @MethodSource("invalidPreconditions")
    void missingOrWeakPreconditionsCannotStartAReview(String ifMatch, HttpStatus status) {
        select(WORKSPACE);

        assertEmpty(controller.accept(17L, ifMatch, null, KEY, null), status);

        verifyNoInteractions(reviewService, repositoryService, membershipService);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "review-17\nforged-trailer"})
    void invalidIdempotencyKeysCannotStartAReview(String key) {
        select(WORKSPACE);

        assertEmpty(controller.accept(17L, ETAG, null, key, null), HttpStatus.BAD_REQUEST);

        verifyNoInteractions(reviewService, repositoryService, membershipService);
    }

    @ParameterizedTest
    @MethodSource("currentHeads")
    void conflictingReviewReturnsProposalActionAndExactHeadsWithoutRetrying(String actualHead)
            throws Exception {
        select(WORKSPACE);
        when(reviewService.reject(17L, WORKSPACE, HEAD, NO_RATIONALE)).thenThrow(
                new BranchHeadConflictException("review/exact", HEAD, actualHead, "Branch changed"));

        var response = controller.reject(17L, ETAG, null, KEY, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(response.getHeaders().getETag())
                .isEqualTo(actualHead == null ? null : '"' + actualHead + '"');
        assertThat(response.getBody()).isEqualTo(new ReviewResponse(
                17L, "REJECT", null, null, null, false,
                "PRECONDITION_FAILED", null, null, null, HEAD, actualHead));
        verify(reviewService).reject(17L, WORKSPACE, HEAD, NO_RATIONALE);
        verifyNoMoreInteractions(reviewService);
    }

    @ParameterizedTest
    @EnumSource(PendingPhase.class)
    void committedReviewWithUnfinishedProjectionOrBookkeepingReturnsAccepted(PendingPhase phase)
            throws Exception {
        select(WORKSPACE);
        ProposalReviewPendingException pending = mock(ProposalReviewPendingException.class);
        when(pending.getAuthority()).thenReturn(authority(WORKSPACE));
        when(pending.getProposalId()).thenReturn(17L);
        when(pending.getIntendedStatus()).thenReturn(ProposalStatus.REJECTED);
        when(pending.getPhase()).thenReturn(phase);
        when(reviewService.reject(17L, WORKSPACE, HEAD, NO_RATIONALE)).thenThrow(pending);

        var response = controller.reject(17L, ETAG, null, KEY, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getETag()).isEqualTo('"' + AUTHORITY + '"');
        assertThat(response.getBody()).isEqualTo(new ReviewResponse(
                17L, null, "REJECTED", AUTHORITY, "UPDATED", true,
                "PENDING_RECOVERY", null, null, phase.name(), null, null));
        verify(reviewService).reject(17L, WORKSPACE, HEAD, NO_RATIONALE);
        verifyNoMoreInteractions(reviewService);
        verifyNoInteractions(repositoryService, membershipService);
    }

    @ParameterizedTest
    @MethodSource("reviewFailures")
    void serviceRejectionAndStorageFailuresHaveStableStatusCodesWithoutRetrying(
            Exception failure, HttpStatus status) throws Exception {
        select(WORKSPACE);
        when(reviewService.revert(17L, WORKSPACE, HEAD, NO_RATIONALE)).thenThrow(failure);

        assertEmpty(controller.revert(17L, ETAG, null, KEY, null), status);

        verify(reviewService).revert(17L, WORKSPACE, HEAD, NO_RATIONALE);
        verifyNoMoreInteractions(reviewService);
        verifyNoInteractions(repositoryService, membershipService);
    }

    private static Stream<Arguments> invalidPreconditions() {
        return Stream.of(
                Arguments.of(null, HttpStatus.PRECONDITION_REQUIRED),
                Arguments.of("W/" + ETAG, HttpStatus.BAD_REQUEST));
    }

    private static Stream<String> currentHeads() {
        return Stream.of(AUTHORITY, null);
    }

    private static Stream<Arguments> reviewFailures() {
        return Stream.of(
                Arguments.of(new ReadOnlyRepositoryContextException("Context is read-only"), HttpStatus.FORBIDDEN),
                Arguments.of(new IllegalArgumentException("Proposal absent in this workspace"), HttpStatus.BAD_REQUEST),
                Arguments.of(new IllegalStateException("Proposal is already PENDING"), HttpStatus.CONFLICT),
                Arguments.of(new IOException("Git storage unavailable"), HttpStatus.SERVICE_UNAVAILABLE));
    }

    private static MutationResult mutation(RepositoryContext context, boolean relationPresent) {
        return new MutationResult(
                authority(context),
                new ProjectionResult(ProjectionOutcome.UPDATED, AUTHORITY, relationPresent));
    }

    private static CommandResult authority(RepositoryContext context) {
        return new CommandResult(context.repositoryId(), context.workspaceId(), context.branch(),
                context.scope(), HEAD, AUTHORITY, ChangeKind.UPDATED, true, KEY);
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
