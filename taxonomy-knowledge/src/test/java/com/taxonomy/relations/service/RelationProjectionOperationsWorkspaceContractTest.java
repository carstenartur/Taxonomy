package com.taxonomy.relations.service;

import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionSourceException;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationProjectionOperationsService.RebuildHeadConflictException;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.ReconciliationResult;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RelationProjectionOperationsWorkspaceContractTest {
    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");
    private static final String HEAD = "a".repeat(40);
    private static final String NEWER = "b".repeat(40);

    private final WorkspaceDslReadPort reads = mock(WorkspaceDslReadPort.class);
    private final WorkspaceDslReadPort.RepositoryRead read = mock(WorkspaceDslReadPort.RepositoryRead.class);
    private final RelationBranchProjectionReadinessService readiness = mock(RelationBranchProjectionReadinessService.class);
    private final RelationBranchProjectionRebuildService rebuild = mock(RelationBranchProjectionRebuildService.class);
    private final RelationProjectionRecoveryService recovery = mock(RelationProjectionRecoveryService.class);
    private final RelationProjectionOperationsService service =
            new RelationProjectionOperationsService(reads, readiness, rebuild, recovery);

    @Test
    void bindsContextOnceAndVerifiesHeadBeforeAnyProjectionWork() throws Exception {
        when(reads.openRead(CONTEXT)).thenReturn(read);
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
        var rebuilt = new RebuildResult("repo-a", "workspace-a", "review", HEAD, 0);
        var ready = new Readiness(ReadinessState.READY, HEAD, HEAD, List.of());
        var reconciled = new ReconciliationResult(HEAD, 0, 0, 0);
        when(rebuild.rebuild(CONTEXT)).thenReturn(rebuilt);
        when(readiness.inspect(CONTEXT)).thenReturn(ready);
        when(recovery.reconcileAfterRebuild(CONTEXT, HEAD)).thenReturn(reconciled);

        var result = service.rebuild(CONTEXT, "  " + HEAD + "  ");

        assertThat(result.rebuild()).isSameAs(rebuilt);
        assertThat(result.readiness()).isSameAs(ready);
        assertThat(result.reconciliation()).isSameAs(reconciled);
        var order = inOrder(reads, read, rebuild, readiness, recovery);
        order.verify(reads).openRead(CONTEXT);
        order.verify(read).verifyExpectedHead(HEAD);
        order.verify(rebuild).rebuild(CONTEXT);
        order.verify(readiness).inspect(CONTEXT);
        order.verify(recovery).reconcileAfterRebuild(CONTEXT, HEAD);
        verifyNoMoreInteractions(reads, read, rebuild, readiness, recovery);
    }

    @Test
    void invalidOrReadOnlyRequestsNeverOpenTheWorkspacePort() {
        for (String head : Arrays.asList(null, "", "  ")) {
            assertThatThrownBy(() -> service.rebuild(CONTEXT, head))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires If-Match");
        }
        var reader = RepositoryContext.centralRead("repo-a", "review", "reader");
        assertThatThrownBy(() -> service.rebuild(reader, HEAD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("writable");
        verifyNoInteractions(reads, read, rebuild, readiness, recovery);
    }

    @Test
    void workspaceConflictKeepsAllMetadataAndStopsBeforeProjection() throws Exception {
        var conflict = new BranchHeadConflictException("review", HEAD, NEWER, "Branch moved");
        when(reads.openRead(CONTEXT)).thenReturn(read);
        when(read.verifyExpectedHead(HEAD)).thenThrow(conflict);

        assertThatThrownBy(() -> service.rebuild(CONTEXT, HEAD)).isSameAs(conflict);
        assertThat(conflict.getBranch()).isEqualTo("review");
        assertThat(conflict.getExpectedHeadCommit()).isEqualTo(HEAD);
        assertThat(conflict.getActualHeadCommit()).isEqualTo(NEWER);
        assertThat(conflict.getMessage()).isEqualTo("Branch moved");
        verifyNoInteractions(rebuild, readiness, recovery);
    }

    @Test
    void ordinaryIoFailureIsNotMisreportedAsAHeadConflict() throws Exception {
        var failure = new IOException("Storage unavailable");
        when(reads.openRead(CONTEXT)).thenReturn(read);
        when(read.verifyExpectedHead(HEAD)).thenThrow(failure);

        assertThatThrownBy(() -> service.rebuild(CONTEXT, HEAD)).isSameAs(failure)
                .isNotInstanceOf(BranchHeadConflictException.class);
        verifyNoInteractions(rebuild, readiness, recovery);
    }

    @Test
    void absentVerifiedHeadCannotTriggerProjectionWrites() throws Exception {
        when(reads.openRead(CONTEXT)).thenReturn(read);
        when(read.verifyExpectedHead(HEAD)).thenReturn(null);

        assertThatThrownBy(() -> service.rebuild(CONTEXT, HEAD))
                .isInstanceOf(BranchProjectionSourceException.class).hasMessageContaining("absent branch");
        verifyNoInteractions(rebuild, readiness, recovery);
    }

    @Test
    void concurrentNewerRebuildCannotSatisfyTheOlderRequest() throws Exception {
        when(reads.openRead(CONTEXT)).thenReturn(read);
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
        when(rebuild.rebuild(CONTEXT)).thenReturn(new RebuildResult("repo-a", "workspace-a", "review", NEWER, 0));

        assertThatThrownBy(() -> service.rebuild(CONTEXT, HEAD))
                .isInstanceOfSatisfying(RebuildHeadConflictException.class, error -> {
                    assertThat(error.getExpectedHeadCommit()).isEqualTo(HEAD);
                    assertThat(error.getActualHeadCommit()).isEqualTo(NEWER);
                });
        verifyNoInteractions(readiness, recovery);
    }

    @Test
    void headMovementDuringReadinessCannotCompleteRecovery() throws Exception {
        when(reads.openRead(CONTEXT)).thenReturn(read);
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
        when(rebuild.rebuild(CONTEXT)).thenReturn(new RebuildResult("repo-a", "workspace-a", "review", HEAD, 0));
        when(readiness.inspect(CONTEXT)).thenReturn(new Readiness(ReadinessState.STALE, NEWER, HEAD, List.of()));

        assertThatThrownBy(() -> service.rebuild(CONTEXT, HEAD))
                .isInstanceOfSatisfying(RebuildHeadConflictException.class, error -> {
                    assertThat(error.getExpectedHeadCommit()).isEqualTo(HEAD);
                    assertThat(error.getActualHeadCommit()).isEqualTo(NEWER);
                });
        verifyNoInteractions(recovery);
    }

    @Test
    void inspectionDoesNotResolveStorageOrVerifyAWritePrecondition() {
        var ready = new Readiness(ReadinessState.NOT_BUILT, HEAD, null, List.of());
        when(readiness.inspect(CONTEXT)).thenReturn(ready);
        when(recovery.pending(CONTEXT)).thenReturn(List.of());

        var result = service.inspect(CONTEXT);

        assertThat(result.readiness()).isSameAs(ready);
        assertThat(result.pendingRecoveries()).isEmpty();
        verifyNoInteractions(reads, read, rebuild);
    }
}
