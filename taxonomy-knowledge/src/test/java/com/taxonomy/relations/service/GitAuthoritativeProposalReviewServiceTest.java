package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.PendingPhase;
import com.taxonomy.relations.service.GitAuthoritativeProposalReviewService.ProposalReviewPendingException;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.ProposalReviewStateStore.ProposalSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitAuthoritativeProposalReviewServiceTest {

    private static final String PREVIOUS = "a".repeat(40);
    private static final String AUTHORITY = "b".repeat(40);

    @Mock
    private ProposalReviewStateStore stateStore;

    @Mock
    private GitAuthoritativeRelationMutationService mutationService;

    private GitAuthoritativeProposalReviewService service;
    private RepositoryContext context;
    private CommandMetadata metadata;

    @BeforeEach
    void setUp() {
        service = new GitAuthoritativeProposalReviewService(
                stateStore, mutationService);
        context = new RepositoryContext(
                "repo-a",
                "workspace-a",
                "review",
                "alice",
                RepositoryScope.WORKSPACE);
        metadata = new CommandMetadata("proposal-review-17", "human review");
    }

    @Test
    void acceptCommitsAndProjectsBeforeAdvancingProposalBookkeeping()
            throws Exception {
        ProposalSnapshot proposal = snapshot(ProposalStatus.PENDING);
        MutationResult mutation = mutation(true);
        when(stateStore.require(17L, context)).thenReturn(proposal);
        when(mutationService.upsert(
                eq(context), eq(PREVIOUS), any(), eq(metadata)))
                .thenReturn(mutation);
        when(stateStore.transition(
                17L, context, ProposalStatus.PENDING, ProposalStatus.ACCEPTED))
                .thenReturn(ProposalStatus.ACCEPTED);

        var result = service.accept(17L, context, PREVIOUS, metadata);

        ArgumentCaptor<RelationDefinition> definition =
                ArgumentCaptor.forClass(RelationDefinition.class);
        verify(mutationService).upsert(
                eq(context), eq(PREVIOUS), definition.capture(), eq(metadata));
        assertThat(definition.getValue().status()).isEqualTo("accepted");
        assertThat(definition.getValue().confidence()).isEqualTo(0.83);
        assertThat(definition.getValue().provenance())
                .isEqualTo("proposal-review");
        assertThat(definition.getValue().extensions())
                .isEqualTo(Map.of("x-proposal-id", "17"));
        assertThat(result.proposalStatus()).isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(result.mutation().authority().authoritativeCommitId())
                .isEqualTo(AUTHORITY);

        InOrder order = inOrder(mutationService, stateStore);
        order.verify(stateStore).require(17L, context);
        order.verify(mutationService).upsert(
                eq(context), eq(PREVIOUS), any(), eq(metadata));
        order.verify(stateStore).transition(
                17L, context, ProposalStatus.PENDING, ProposalStatus.ACCEPTED);
    }

    @Test
    void rejectPersistsARejectedGitDecisionAndInactiveProjection()
            throws Exception {
        when(stateStore.require(17L, context))
                .thenReturn(snapshot(ProposalStatus.PENDING));
        when(mutationService.upsert(
                eq(context), eq(PREVIOUS), any(), eq(metadata)))
                .thenReturn(mutation(false));
        when(stateStore.transition(
                17L, context, ProposalStatus.PENDING, ProposalStatus.REJECTED))
                .thenReturn(ProposalStatus.REJECTED);

        var result = service.reject(17L, context, PREVIOUS, metadata);

        ArgumentCaptor<RelationDefinition> definition =
                ArgumentCaptor.forClass(RelationDefinition.class);
        verify(mutationService).upsert(
                eq(context), eq(PREVIOUS), definition.capture(), eq(metadata));
        assertThat(definition.getValue().status()).isEqualTo("rejected");
        assertThat(result.mutation().projection().relationPresent()).isFalse();
        assertThat(result.proposalStatus()).isEqualTo(ProposalStatus.REJECTED);
    }

    @Test
    void revertRemovesTheCurrentDecisionBeforeReturningProposalToPending()
            throws Exception {
        when(stateStore.require(17L, context))
                .thenReturn(snapshot(ProposalStatus.ACCEPTED));
        when(mutationService.remove(
                eq(context), eq(PREVIOUS), any(), eq(metadata)))
                .thenReturn(mutation(false));
        when(stateStore.transition(
                17L, context, ProposalStatus.ACCEPTED, ProposalStatus.PENDING))
                .thenReturn(ProposalStatus.PENDING);

        var result = service.revert(17L, context, PREVIOUS, metadata);

        ArgumentCaptor<RelationIdentity> identity =
                ArgumentCaptor.forClass(RelationIdentity.class);
        verify(mutationService).remove(
                eq(context), eq(PREVIOUS), identity.capture(), eq(metadata));
        assertThat(identity.getValue())
                .extracting(
                        RelationIdentity::sourceId,
                        RelationIdentity::relationType,
                        RelationIdentity::targetId)
                .containsExactly("APP-1", "USES", "SVC-1");
        assertThat(result.proposalStatus()).isEqualTo(ProposalStatus.PENDING);
    }

    @Test
    void projectionFailureExposesAuthorityAndNeverAdvancesProposalStatus()
            throws Exception {
        CommandResult authority = authority();
        when(stateStore.require(17L, context))
                .thenReturn(snapshot(ProposalStatus.PENDING));
        when(mutationService.upsert(
                eq(context), eq(PREVIOUS), any(), eq(metadata)))
                .thenThrow(new ProjectionPendingException(
                        authority,
                        new IllegalStateException("database unavailable")));

        assertThatThrownBy(() -> service.accept(
                17L, context, PREVIOUS, metadata))
                .isInstanceOfSatisfying(
                        ProposalReviewPendingException.class,
                        error -> {
                            assertThat(error.getAuthority()).isEqualTo(authority);
                            assertThat(error.getIntendedStatus())
                                    .isEqualTo(ProposalStatus.ACCEPTED);
                            assertThat(error.getPhase())
                                    .isEqualTo(PendingPhase.PROJECTION);
                        });
        verify(stateStore, never()).transition(
                any(), any(), any(), any());
    }

    @Test
    void bookkeepingFailureRemainsRecoverableAfterProjectedGitSuccess()
            throws Exception {
        MutationResult mutation = mutation(true);
        when(stateStore.require(17L, context))
                .thenReturn(snapshot(ProposalStatus.PENDING));
        when(mutationService.upsert(
                eq(context), eq(PREVIOUS), any(), eq(metadata)))
                .thenReturn(mutation);
        when(stateStore.transition(
                17L, context, ProposalStatus.PENDING, ProposalStatus.ACCEPTED))
                .thenThrow(new IllegalStateException("proposal row unavailable"));

        assertThatThrownBy(() -> service.accept(
                17L, context, PREVIOUS, metadata))
                .isInstanceOfSatisfying(
                        ProposalReviewPendingException.class,
                        error -> {
                            assertThat(error.getAuthority())
                                    .isEqualTo(mutation.authority());
                            assertThat(error.getPhase())
                                    .isEqualTo(PendingPhase.PROPOSAL_BOOKKEEPING);
                        });
    }

    private static ProposalSnapshot snapshot(ProposalStatus status) {
        return new ProposalSnapshot(
                17L,
                "APP-1",
                "SVC-1",
                RelationType.USES,
                status,
                0.83,
                "reviewed evidence",
                "llm");
    }

    private static MutationResult mutation(boolean relationPresent) {
        return new MutationResult(
                authority(),
                new RelationDecisionProjectionService.ProjectionResult(
                        RelationDecisionProjectionService.ProjectionOutcome.UPDATED,
                        AUTHORITY,
                        relationPresent));
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
                "proposal-review-17");
    }
}
