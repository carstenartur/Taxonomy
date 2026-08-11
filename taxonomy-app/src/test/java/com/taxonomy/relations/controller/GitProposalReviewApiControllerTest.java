package com.taxonomy.relations.controller;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewProjectionPendingException;
import com.taxonomy.relations.service.ProposalReviewDecision;
import com.taxonomy.relations.service.ProposalReviewProjectionWriter;
import com.taxonomy.relations.service.RelationDecisionProjectionService;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryMembershipService;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitProposalReviewApiControllerTest {

    private static final String PREVIOUS = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);

    @Mock
    private GitAuthoritativeProposalReviewService reviewService;

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private SystemRepositoryService repositoryService;

    @Mock
    private RepositoryMembershipService membershipService;

    private GitProposalReviewApiController controller;
    private RepositoryContext context;

    @BeforeEach
    void setUp() {
        controller = new GitProposalReviewApiController(
                reviewService,
                workspaceResolver,
                repositoryService,
                membershipService);
        context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        when(workspaceResolver.resolveCurrentRepositoryContext())
                .thenReturn(context);
    }

    @Test
    void returnsProposalDecisionAndAuthoritativeCommitEtag() throws Exception {
        when(reviewService.review(
                eq(context),
                eq(17L),
                eq(PREVIOUS),
                eq("proposal-17"),
                eq(ProposalReviewDecision.ACCEPT),
                eq("Reviewed by architecture board")))
                .thenReturn(reviewResult());

        var response = controller.review(
                17L,
                '"' + PREVIOUS + '"',
                null,
                "proposal-17",
                new GitProposalReviewApiController.ReviewRequest(
                        "accept",
                        "Reviewed by architecture board"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getETag())
                .isEqualTo('"' + AUTHORITY + '"');
        assertThat(response.getBody().proposalId()).isEqualTo(17L);
        assertThat(response.getBody().proposalStatus())
                .isEqualTo("ACCEPTED");
        assertThat(response.getBody().projectionStatus())
                .isEqualTo("PROJECTED");
    }

    @Test
    void missingPreconditionCannotReachReviewService() {
        var response = controller.review(
                17L,
                null,
                null,
                "proposal-17",
                new GitProposalReviewApiController.ReviewRequest(
                        "accept", null));

        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        verifyNoInteractions(reviewService);
    }

    @Test
    void proposalProjectionFailureReturnsAcceptedWithAuthorityEtag()
            throws Exception {
        when(reviewService.review(
                eq(context),
                eq(17L),
                eq(PREVIOUS),
                eq("proposal-17"),
                eq(ProposalReviewDecision.ACCEPT),
                isNull()))
                .thenThrow(new ProposalReviewProjectionPendingException(
                        17L,
                        authority(),
                        new IllegalStateException("database unavailable")));

        var response = controller.review(
                17L,
                '"' + PREVIOUS + '"',
                null,
                "proposal-17",
                new GitProposalReviewApiController.ReviewRequest(
                        "accept", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getETag())
                .isEqualTo('"' + AUTHORITY + '"');
        assertThat(response.getBody().proposalId()).isEqualTo(17L);
        assertThat(response.getBody().authoritativeCommitId())
                .isEqualTo(AUTHORITY);
        assertThat(response.getBody().projectionStatus())
                .isEqualTo("PENDING_REBUILD");
    }

    private static GitAuthoritativeProposalReviewService.ReviewResult reviewResult() {
        return new GitAuthoritativeProposalReviewService.ReviewResult(
                ProposalReviewDecision.ACCEPT,
                authority(),
                new RelationDecisionProjectionService.ProjectionResult(
                        RelationDecisionProjectionService.ProjectionOutcome.UPDATED,
                        AUTHORITY,
                        true),
                new ProposalReviewProjectionWriter.ProjectionResult(
                        ProposalReviewProjectionWriter.ProjectionOutcome.UPDATED,
                        17L,
                        ProposalStatus.ACCEPTED,
                        "review",
                        AUTHORITY,
                        "proposal-17",
                        Instant.parse("2026-08-11T20:00:00Z")));
    }

    private static CommandResult authority() {
        return new CommandResult(
                "repo-a",
                "workspace-a",
                "review",
                RepositoryScope.WORKSPACE,
                PREVIOUS,
                AUTHORITY,
                ChangeKind.UPDATED,
                true,
                "proposal-17");
    }
}
