package com.taxonomy.relations.service;

import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.storage.DslWorkspaceReadAdapter;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildVerificationException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RecoveryReconciliationPendingException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.ReconciliationResult;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationProjectionOperationsServiceTest {

    private DslGitRepositoryFactory repositoryFactory;

    @AfterEach
    void closeRepositories() {
        if (repositoryFactory != null) {
            repositoryFactory.close();
        }
    }

    @Test
    void rebuildRequiresTheExactHeadThenProvesReadinessBeforeReconciliation()
            throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        String head = repositoryFactory.resolveRepository(context).commitDsl(
                "review",
                "element APP-1 type Application {\n}\n",
                "alice",
                "Seed branch");
        RelationBranchProjectionReadinessService readinessService =
                mock(RelationBranchProjectionReadinessService.class);
        RelationBranchProjectionRebuildService rebuildService =
                mock(RelationBranchProjectionRebuildService.class);
        RelationProjectionRecoveryService recoveryService =
                mock(RelationProjectionRecoveryService.class);
        RebuildResult rebuilt = new RebuildResult(
                "repo-a", "workspace-a", "review", head, 0);
        ReconciliationResult reconciliation = new ReconciliationResult(
                head, 1, 1, 0);
        Readiness readiness = new Readiness(
                ReadinessState.READY, head, head, List.of());
        when(rebuildService.rebuildAtVerifiedHead(eq(context), any(), eq(head))).thenReturn(rebuilt);
        when(readinessService.inspect(context)).thenReturn(readiness);
        when(recoveryService.reconcileAfterRebuild(context, head))
                .thenReturn(reconciliation);

        var operation = new RelationProjectionOperationsService(
                new DslWorkspaceReadAdapter(repositoryFactory),
                readinessService,
                rebuildService,
                recoveryService).rebuild(context, head);

        assertThat(operation.rebuild()).isSameAs(rebuilt);
        assertThat(operation.reconciliation()).isSameAs(reconciliation);
        assertThat(operation.readiness()).isSameAs(readiness);
        InOrder order = inOrder(
                rebuildService, readinessService, recoveryService);
        order.verify(rebuildService).rebuildAtVerifiedHead(eq(context), any(), eq(head));
        order.verify(readinessService).inspect(context);
        order.verify(recoveryService).reconcileAfterRebuild(context, head);
    }

    @Test
    void branchCreationPreconditionIsRejectedBeforeRepositoryOrProjectionAccess()
            throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = mock(DslGitRepositoryFactory.class);
        RelationBranchProjectionReadinessService readinessService =
                mock(RelationBranchProjectionReadinessService.class);
        RelationBranchProjectionRebuildService rebuildService =
                mock(RelationBranchProjectionRebuildService.class);
        RelationProjectionRecoveryService recoveryService =
                mock(RelationProjectionRecoveryService.class);

        assertThatThrownBy(() -> new RelationProjectionOperationsService(
                new DslWorkspaceReadAdapter(repositoryFactory),
                readinessService,
                rebuildService,
                recoveryService).rebuild(context, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires If-Match");

        verify(repositoryFactory, never()).resolveRepository(context);
        verifyNoInteractions(rebuildService);
        verify(readinessService, never()).inspect(context);
        verify(recoveryService, never()).reconcileAfterRebuild(
                context, null);
    }

    @Test
    void advanceAfterAdmissionCannotReplaceTheProjectionForAnotherCommit() throws Exception {
        var context = RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        var git = repositoryFactory.resolveRepository(context);
        String expected = git.commitDsl("review", "", "alice", "Initial revision");
        var selected = new DslWorkspaceReadAdapter(repositoryFactory).openRead(context);
        var changing = mock(WorkspaceDslReadPort.RepositoryRead.class, delegatesTo(selected));
        doAnswer(call -> {
            String verified = selected.verifyExpectedHead(expected);
            git.commitDsl("review", "element APP-1 type Application {\n}\n", "alice", "Advance branch");
            return verified;
        }).when(changing).verifyExpectedHead(expected);
        WorkspaceDslReadPort reads = ignored -> changing;
        var writer = mock(RelationBranchProjectionRebuildWriter.class);
        when(writer.replace(eq(context), anyString(), anyList())).thenAnswer(call ->
                new RebuildResult(context.repositoryId(), context.workspaceId(), context.branch(), call.getArgument(1), 0));
        var readiness = mock(RelationBranchProjectionReadinessService.class);
        var recovery = mock(RelationProjectionRecoveryService.class);
        var operations = new RelationProjectionOperationsService(reads, readiness,
                new RelationBranchProjectionRebuildService(reads, writer), recovery);

        assertThatThrownBy(() -> operations.rebuild(context, expected))
                .isInstanceOfSatisfying(BranchHeadConflictException.class, conflict -> {
                    assertThat(conflict.getExpectedHeadCommit()).isEqualTo(expected);
                    assertThat(conflict.getActualHeadCommit()).isNotEqualTo(expected);
                });
        verifyNoInteractions(writer, readiness, recovery);
    }

    @Test
    void staleExpectedHeadIsRejectedBeforeAnyProjectionWrite() throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        repositoryFactory.resolveRepository(context).commitDsl(
                "review",
                "element APP-1 type Application {\n}\n",
                "alice",
                "Seed branch");
        RelationBranchProjectionReadinessService readinessService =
                mock(RelationBranchProjectionReadinessService.class);
        RelationBranchProjectionRebuildService rebuildService =
                mock(RelationBranchProjectionRebuildService.class);
        RelationProjectionRecoveryService recoveryService =
                mock(RelationProjectionRecoveryService.class);
        RelationProjectionOperationsService service =
                new RelationProjectionOperationsService(
                        new DslWorkspaceReadAdapter(repositoryFactory),
                        readinessService,
                        rebuildService,
                        recoveryService);

        assertThatThrownBy(() -> service.rebuild(
                context, "a".repeat(40)))
                .isInstanceOf(BranchHeadConflictException.class);
        verifyNoInteractions(rebuildService);
    }

    @Test
    void corruptReadModelNeverCompletesRecoveryAndIsNotAHeadConflict()
            throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        String head = repositoryFactory.resolveRepository(context).commitDsl(
                "review",
                "element APP-1 type Application {\n}\n",
                "alice",
                "Seed branch");
        RelationBranchProjectionReadinessService readinessService =
                mock(RelationBranchProjectionReadinessService.class);
        RelationBranchProjectionRebuildService rebuildService =
                mock(RelationBranchProjectionRebuildService.class);
        RelationProjectionRecoveryService recoveryService =
                mock(RelationProjectionRecoveryService.class);
        RebuildResult rebuilt = new RebuildResult(
                "repo-a", "workspace-a", "review", head, 1);
        Readiness corrupt = new Readiness(
                ReadinessState.CORRUPT, head, head, List.of());
        when(rebuildService.rebuildAtVerifiedHead(eq(context), any(), eq(head))).thenReturn(rebuilt);
        when(readinessService.inspect(context)).thenReturn(corrupt);

        assertThatThrownBy(() -> new RelationProjectionOperationsService(
                new DslWorkspaceReadAdapter(repositoryFactory),
                readinessService,
                rebuildService,
                recoveryService).rebuild(context, head))
                .isInstanceOfSatisfying(
                        RebuildVerificationException.class,
                        error -> {
                            assertThat(error.getRebuild()).isSameAs(rebuilt);
                            assertThat(error.getReadiness()).isSameAs(corrupt);
                        });
        verify(recoveryService, never()).reconcileAfterRebuild(context, head);
    }

    @Test
    void reconciliationFailureStillExposesTheVerifiedRebuildCommit()
            throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        String head = repositoryFactory.resolveRepository(context).commitDsl(
                "review",
                "element APP-1 type Application {\n}\n",
                "alice",
                "Seed branch");
        RelationBranchProjectionReadinessService readinessService =
                mock(RelationBranchProjectionReadinessService.class);
        RelationBranchProjectionRebuildService rebuildService =
                mock(RelationBranchProjectionRebuildService.class);
        RelationProjectionRecoveryService recoveryService =
                mock(RelationProjectionRecoveryService.class);
        RebuildResult rebuilt = new RebuildResult(
                "repo-a", "workspace-a", "review", head, 0);
        Readiness readiness = new Readiness(
                ReadinessState.READY, head, head, List.of());
        when(rebuildService.rebuildAtVerifiedHead(eq(context), any(), eq(head))).thenReturn(rebuilt);
        when(readinessService.inspect(context)).thenReturn(readiness);
        when(recoveryService.reconcileAfterRebuild(context, head))
                .thenThrow(new IllegalStateException("recovery unavailable"));

        assertThatThrownBy(() -> new RelationProjectionOperationsService(
                new DslWorkspaceReadAdapter(repositoryFactory),
                readinessService,
                rebuildService,
                recoveryService).rebuild(context, head))
                .isInstanceOfSatisfying(
                        RecoveryReconciliationPendingException.class,
                        error -> assertThat(error.getRebuild())
                                .isSameAs(rebuilt));
    }
}
