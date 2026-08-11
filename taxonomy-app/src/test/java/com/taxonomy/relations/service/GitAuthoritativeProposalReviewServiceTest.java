package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewConflictException;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewProjectionPendingException;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.ProposalReviewProjectionWriter.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.relations.service.RelationProposalReviewCandidateReader.Candidate;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitAuthoritativeProposalReviewServiceTest {

    private static final String PREVIOUS = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);

    @Mock
    private RelationProposalReviewCandidateReader candidateReader;

    @Mock
    private GitAuthoritativeRelationMutationService relationMutationService;

    @Mock
    private ProposalReviewProjectionWriter proposalProjectionWriter;

    @Test
    void acceptsProposalInGitBeforeProjectingProposalStatus() throws Exception {
        RepositoryContext context = context();
        Candidate candidate = candidate(ProposalStatus.PENDING, null, null, null);
        MutationResult relationResult = relationResult(true);
        ProposalReviewProjectionWriter.ProjectionResult proposalResult =
                proposalResult(ProposalStatus.ACCEPTED, ProjectionOutcome.UPDATED);
        when(candidateReader.read(context, 17L)).thenReturn(candidate);
        when(relationMutationService.upsert(
                eq(context), eq(PREVIOUS), any(RelationDefinition.class),
                any(CommandMetadata.class)))
                .thenReturn(relationResult);
        when(proposalProjectionWriter.project(any()))
                .thenReturn(proposalResult);
        GitAuthoritativeProposalReviewService service = service();

        var result = service.review(
                context,
                17L,
                PREVIOUS,
                "proposal-17",
                ProposalReviewDecision.ACCEPT,
                "Human accepted the proposed link");

        ArgumentCaptor<RelationDefinition> definition =
                ArgumentCaptor.forClass(RelationDefinition.class);
        ArgumentCaptor<CommandMetadata> metadata =
                ArgumentCaptor.forClass(CommandMetadata.class);
        InOrder order = inOrder(
                relationMutationService, proposalProjectionWriter);
        order.verify(relationMutationService).upsert(
                eq(context), eq(PREVIOUS), definition.capture(), metadata.capture());
        order.verify(proposalProjectionWriter).project(any());
        assertThat(definition.getValue().status()).isEqualTo("accepted");
        assertThat(definition.getValue().confidence()).isEqualTo(0.91);
        assertThat(definition.getValue().provenance()).isEqualTo("llm-and-human");
        assertThat(definition.getValue().extensions())
                .containsEntry("x-source-proposal-id", "17")
                .containsEntry("x-review-decision", "accepted");
        assertThat(metadata.getValue().causationId()).isEqualTo("proposal-17");
        assertThat(metadata.getValue().rationale())
                .isEqualTo("Human accepted the proposed link");
        assertThat(result.authority().authoritativeCommitId())
                .isEqualTo(AUTHORITY);
        assertThat(result.proposalProjection().status())
                .isEqualTo(ProposalStatus.ACCEPTED);
    }

    @Test
    void rejectionIsAnAuditableGitUpsertNotAPhysicalRemoval() throws Exception {
        RepositoryContext context = context();
        when(candidateReader.read(context, 17L))
                .thenReturn(candidate(ProposalStatus.PENDING, null, null, null));
        when(relationMutationService.upsert(
                eq(context), eq(PREVIOUS), any(RelationDefinition.class),
                any(CommandMetadata.class)))
                .thenReturn(relationResult(false));
        when(proposalProjectionWriter.project(any()))
                .thenReturn(proposalResult(
                        ProposalStatus.REJECTED, ProjectionOutcome.UPDATED));
        GitAuthoritativeProposalReviewService service = service();

        service.review(
                context,
                17L,
                PREVIOUS,
                "proposal-17-reject",
                ProposalReviewDecision.REJECT,
                null);

        ArgumentCaptor<RelationDefinition> definition =
                ArgumentCaptor.forClass(RelationDefinition.class);
        relationMutationService.upsert(
                eq(context), eq(PREVIOUS), definition.capture(),
                any(CommandMetadata.class));
        org.mockito.Mockito.verify(relationMutationService).upsert(
                eq(context), eq(PREVIOUS), definition.capture(),
                any(CommandMetadata.class));
        assertThat(definition.getValue().status()).isEqualTo("rejected");
        assertThat(definition.getValue().extensions())
                .containsEntry("x-review-decision", "rejected");
    }

    @Test
    void conflictingAlreadyReviewedProposalStopsBeforeGit() {
        RepositoryContext context = context();
        when(candidateReader.read(context, 17L))
                .thenReturn(candidate(
                        ProposalStatus.ACCEPTED,
                        "review",
                        AUTHORITY,
                        "proposal-17"));
        GitAuthoritativeProposalReviewService service = service();

        assertThatThrownBy(() -> service.review(
                context,
                17L,
                AUTHORITY,
                "proposal-18",
                ProposalReviewDecision.REJECT,
                null))
                .isInstanceOf(ProposalReviewConflictException.class)
                .hasMessageContaining("already reviewed");
        verifyNoInteractions(relationMutationService, proposalProjectionWriter);
    }

    @Test
    void proposalProjectionFailurePreservesAndExposesGitAuthority()
            throws Exception {
        RepositoryContext context = context();
        when(candidateReader.read(context, 17L))
                .thenReturn(candidate(ProposalStatus.PENDING, null, null, null));
        when(relationMutationService.upsert(
                eq(context), eq(PREVIOUS), any(RelationDefinition.class),
                any(CommandMetadata.class)))
                .thenReturn(relationResult(true));
        when(proposalProjectionWriter.project(any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        GitAuthoritativeProposalReviewService service = service();

        assertThatThrownBy(() -> service.review(
                context,
                17L,
                PREVIOUS,
                "proposal-17",
                ProposalReviewDecision.ACCEPT,
                null))
                .isInstanceOf(ProposalReviewProjectionPendingException.class)
                .satisfies(error -> assertThat(
                        ((ProposalReviewProjectionPendingException) error)
                                .getAuthority().authoritativeCommitId())
                        .isEqualTo(AUTHORITY))
                .hasMessageContaining("requires recovery");
    }

    private GitAuthoritativeProposalReviewService service() {
        return new GitAuthoritativeProposalReviewService(
                candidateReader,
                relationMutationService,
                proposalProjectionWriter);
    }

    private static RepositoryContext context() {
        return new RepositoryContext(
                "repo-a",
                "workspace-a",
                "review",
                "alice",
                RepositoryScope.WORKSPACE);
    }

    private static Candidate candidate(
            ProposalStatus status,
            String reviewBranch,
            String reviewCommit,
            String reviewCausationId) {
        return new Candidate(
                17L,
                "repo-a",
                "workspace-a",
                "APP-1",
                "SVC-1",
                RelationType.USES,
                status,
                0.91,
                "Original proposal rationale",
                "llm-and-human",
                reviewBranch,
                reviewCommit,
                reviewCausationId);
    }

    private static MutationResult relationResult(boolean present) {
        CommandResult authority = new CommandResult(
                "repo-a",
                "workspace-a",
                "review",
                RepositoryScope.WORKSPACE,
                PREVIOUS,
                AUTHORITY,
                ChangeKind.UPDATED,
                true,
                present ? "proposal-17" : "proposal-17-reject");
        ProjectionResult projection = new ProjectionResult(
                RelationDecisionProjectionService.ProjectionOutcome.UPDATED,
                AUTHORITY,
                present);
        return new MutationResult(authority, projection);
    }

    private static ProposalReviewProjectionWriter.ProjectionResult proposalResult(
            ProposalStatus status,
            ProjectionOutcome outcome) {
        return new ProposalReviewProjectionWriter.ProjectionResult(
                outcome,
                17L,
                status,
                "review",
                AUTHORITY,
                status == ProposalStatus.ACCEPTED
                        ? "proposal-17" : "proposal-17-reject",
                Instant.parse("2026-08-11T20:00:00Z"));
    }
}
