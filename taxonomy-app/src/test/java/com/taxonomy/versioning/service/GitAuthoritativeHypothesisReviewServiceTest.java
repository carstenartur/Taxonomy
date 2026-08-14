package com.taxonomy.versioning.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionResult;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.HypothesisReviewPendingException;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.PendingPhase;
import com.taxonomy.versioning.service.GitAuthoritativeHypothesisReviewService.ReviewAction;
import com.taxonomy.versioning.service.HypothesisReviewStateStore.HypothesisSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitAuthoritativeHypothesisReviewServiceTest {

    private static final String HEAD_A = "a".repeat(40);
    private static final String HEAD_B = "b".repeat(40);

    private HypothesisReviewStateStore stateStore;
    private GitAuthoritativeRelationMutationService mutationService;
    private GitAuthoritativeHypothesisReviewService service;
    private RepositoryContext context;
    private CommandMetadata metadata;

    @BeforeEach
    void setUp() {
        stateStore = mock(HypothesisReviewStateStore.class);
        mutationService = mock(GitAuthoritativeRelationMutationService.class);
        service = new GitAuthoritativeHypothesisReviewService(
                stateStore, mutationService);
        context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "draft", "alice");
        metadata = new CommandMetadata("cause-42", "review evidence");
    }

    @Test
    void acceptCommitsAndProjectsBeforeChangingBookkeeping() throws Exception {
        HypothesisSnapshot snapshot = snapshot(HypothesisStatus.PROVISIONAL);
        MutationResult mutation = mutation(ChangeKind.ADDED, true);
        RelationHypothesis accepted = entity(HypothesisStatus.ACCEPTED);
        when(stateStore.require(42L, context)).thenReturn(snapshot);
        when(mutationService.upsert(
                eq(context),
                eq(HEAD_A),
                any(RelationDefinition.class),
                eq(metadata)))
                .thenReturn(mutation);
        when(stateStore.transition(
                42L,
                context,
                HypothesisStatus.PROVISIONAL,
                HypothesisStatus.ACCEPTED))
                .thenReturn(accepted);

        var result = service.accept(42L, context, HEAD_A, metadata);

        assertThat(result.hypothesis()).isSameAs(accepted);
        assertThat(result.action()).isEqualTo(ReviewAction.ACCEPT);
        assertThat(result.mutation().authority().authoritativeCommitId())
                .isEqualTo(HEAD_B);

        ArgumentCaptor<RelationDefinition> definitionCaptor =
                ArgumentCaptor.forClass(RelationDefinition.class);
        verify(mutationService).upsert(
                eq(context),
                eq(HEAD_A),
                definitionCaptor.capture(),
                eq(metadata));
        RelationDefinition definition = definitionCaptor.getValue();
        assertThat(definition.identity()).isEqualTo(
                new RelationIdentity("CR", "REALIZES", "CO"));
        assertThat(definition.status()).isEqualTo("accepted");
        assertThat(definition.provenance()).isEqualTo("hypothesis-review");
        assertThat(definition.extensions())
                .containsEntry("x-hypothesis-id", "42");

        InOrder order = inOrder(stateStore, mutationService);
        order.verify(stateStore).require(42L, context);
        order.verify(mutationService).upsert(
                eq(context), eq(HEAD_A), any(), eq(metadata));
        order.verify(stateStore).transition(
                42L,
                context,
                HypothesisStatus.PROVISIONAL,
                HypothesisStatus.ACCEPTED);
    }

    @Test
    void rejectCreatesAnAuthoritativeRejectedDecision() throws Exception {
        when(stateStore.require(42L, context))
                .thenReturn(snapshot(HypothesisStatus.PROPOSED));
        when(mutationService.upsert(
                eq(context), eq(HEAD_A), any(), eq(metadata)))
                .thenReturn(mutation(ChangeKind.ADDED, false));
        when(stateStore.transition(
                42L,
                context,
                HypothesisStatus.PROPOSED,
                HypothesisStatus.REJECTED))
                .thenReturn(entity(HypothesisStatus.REJECTED));

        var result = service.reject(42L, context, HEAD_A, metadata);

        assertThat(result.hypothesis().getStatus())
                .isEqualTo(HypothesisStatus.REJECTED);
        ArgumentCaptor<RelationDefinition> definitionCaptor =
                ArgumentCaptor.forClass(RelationDefinition.class);
        verify(mutationService).upsert(
                eq(context), eq(HEAD_A), definitionCaptor.capture(), eq(metadata));
        assertThat(definitionCaptor.getValue().status()).isEqualTo("rejected");
    }

    @Test
    void revertRemovesTheReviewedDecisionBeforeRestoringProvisionalStatus()
            throws Exception {
        when(stateStore.require(42L, context))
                .thenReturn(snapshot(HypothesisStatus.ACCEPTED));
        when(mutationService.remove(
                eq(context), eq(HEAD_A), any(), eq(metadata)))
                .thenReturn(mutation(ChangeKind.REMOVED, false));
        when(stateStore.transition(
                42L,
                context,
                HypothesisStatus.ACCEPTED,
                HypothesisStatus.PROVISIONAL))
                .thenReturn(entity(HypothesisStatus.PROVISIONAL));

        var result = service.revert(42L, context, HEAD_A, metadata);

        assertThat(result.action()).isEqualTo(ReviewAction.REVERT);
        assertThat(result.hypothesis().getStatus())
                .isEqualTo(HypothesisStatus.PROVISIONAL);
        verify(mutationService).remove(
                eq(context),
                eq(HEAD_A),
                eq(new RelationIdentity("CR", "REALIZES", "CO")),
                eq(metadata));
    }

    @Test
    void projectionFailureRetainsGitAuthorityAndLeavesBookkeepingUntouched()
            throws Exception {
        CommandResult authority = authority(ChangeKind.ADDED);
        when(stateStore.require(42L, context))
                .thenReturn(snapshot(HypothesisStatus.PROVISIONAL));
        when(mutationService.upsert(
                eq(context), eq(HEAD_A), any(), eq(metadata)))
                .thenThrow(new ProjectionPendingException(
                        authority, new IllegalStateException("projection failed")));

        assertThatThrownBy(() -> service.accept(
                42L, context, HEAD_A, metadata))
                .isInstanceOfSatisfying(
                        HypothesisReviewPendingException.class,
                        error -> {
                            assertThat(error.getAuthority()).isSameAs(authority);
                            assertThat(error.getPhase())
                                    .isEqualTo(PendingPhase.PROJECTION);
                            assertThat(error.getIntendedStatus())
                                    .isEqualTo(HypothesisStatus.ACCEPTED);
                        });
        verify(stateStore, never()).transition(
                any(), any(), any(), any());
    }

    @Test
    void bookkeepingFailureIsReportedAfterTheAuthorityCommit() throws Exception {
        MutationResult mutation = mutation(ChangeKind.ADDED, true);
        when(stateStore.require(42L, context))
                .thenReturn(snapshot(HypothesisStatus.PROVISIONAL));
        when(mutationService.upsert(
                eq(context), eq(HEAD_A), any(), eq(metadata)))
                .thenReturn(mutation);
        when(stateStore.transition(
                42L,
                context,
                HypothesisStatus.PROVISIONAL,
                HypothesisStatus.ACCEPTED))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.accept(
                42L, context, HEAD_A, metadata))
                .isInstanceOfSatisfying(
                        HypothesisReviewPendingException.class,
                        error -> {
                            assertThat(error.getAuthority())
                                    .isSameAs(mutation.authority());
                            assertThat(error.getPhase())
                                    .isEqualTo(PendingPhase.HYPOTHESIS_BOOKKEEPING);
                        });
    }

    @Test
    void terminalStatusIsRejectedBeforeAnyGitMutation() throws Exception {
        when(stateStore.require(42L, context))
                .thenReturn(snapshot(HypothesisStatus.ACCEPTED));

        assertThatThrownBy(() -> service.reject(
                42L, context, HEAD_A, metadata))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reject");
        verify(mutationService, never()).upsert(any(), any(), any(), any());
        verify(mutationService, never()).remove(any(), any(), any(), any());
    }

    private HypothesisSnapshot snapshot(HypothesisStatus status) {
        return new HypothesisSnapshot(
                42L,
                "CR",
                "CO",
                RelationType.REALIZES,
                0.82,
                status);
    }

    private RelationHypothesis entity(HypothesisStatus status) {
        RelationHypothesis hypothesis = new RelationHypothesis();
        hypothesis.setId(42L);
        hypothesis.setRepositoryId(context.repositoryId());
        hypothesis.setWorkspaceId(context.workspaceId());
        hypothesis.setSourceNodeId("CR");
        hypothesis.setTargetNodeId("CO");
        hypothesis.setRelationType(RelationType.REALIZES);
        hypothesis.setConfidence(0.82);
        hypothesis.setStatus(status);
        return hypothesis;
    }

    private MutationResult mutation(ChangeKind changeKind, boolean present) {
        return new MutationResult(
                authority(changeKind),
                new ProjectionResult(
                        ProjectionOutcome.CREATED,
                        HEAD_B,
                        present));
    }

    private CommandResult authority(ChangeKind changeKind) {
        return new CommandResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                context.scope(),
                HEAD_A,
                HEAD_B,
                changeKind,
                changeKind != ChangeKind.UNCHANGED,
                metadata.causationId());
    }
}
