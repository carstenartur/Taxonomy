package com.taxonomy.relations.service;

import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.BranchHeadConflictException;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildVerificationException;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RecoveryReconciliationPendingException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.ReconciliationResult;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        when(rebuildService.rebuild(context)).thenReturn(rebuilt);
        when(readinessService.inspect(context)).thenReturn(readiness);
        when(recoveryService.reconcileAfterRebuild(context, head))
                .thenReturn(reconciliation);

        var operation = new RelationProjectionOperationsService(
                repositoryFactory,
                readinessService,
                rebuildService,
                recoveryService).rebuild(context, head);

        assertThat(operation.rebuild()).isSameAs(rebuilt);
        assertThat(operation.reconciliation()).isSameAs(reconciliation);
        assertThat(operation.readiness()).isSameAs(readiness);
        InOrder order = inOrder(
                rebuildService, readinessService, recoveryService);
        order.verify(rebuildService).rebuild(context);
        order.verify(readinessService).inspect(context);
        order.verify(recoveryService).reconcileAfterRebuild(context, head);
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
                        repositoryFactory,
                        readinessService,
                        rebuildService,
                        recoveryService);

        assertThatThrownBy(() -> service.rebuild(
                context, "a".repeat(40)))
                .isInstanceOf(BranchHeadConflictException.class);
        verify(rebuildService, never()).rebuild(context);
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
        when(rebuildService.rebuild(context)).thenReturn(rebuilt);
        when(readinessService.inspect(context)).thenReturn(corrupt);

        assertThatThrownBy(() -> new RelationProjectionOperationsService(
                repositoryFactory,
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
        when(rebuildService.rebuild(context)).thenReturn(rebuilt);
        when(readinessService.inspect(context)).thenReturn(readiness);
        when(recoveryService.reconcileAfterRebuild(context, head))
                .thenThrow(new IllegalStateException("recovery unavailable"));

        assertThatThrownBy(() -> new RelationProjectionOperationsService(
                repositoryFactory,
                readinessService,
                rebuildService,
                recoveryService).rebuild(context, head))
                .isInstanceOfSatisfying(
                        RecoveryReconciliationPendingException.class,
                        error -> assertThat(error.getRebuild())
                                .isSameAs(rebuilt));
    }
}
