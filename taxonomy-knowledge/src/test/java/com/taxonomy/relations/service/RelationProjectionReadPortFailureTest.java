package com.taxonomy.relations.service;

import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.RelationProjectionNotReadyException;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.BranchProjectionSourceException;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceDslReadPort.RepositoryRead;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RelationProjectionReadPortFailureTest {
    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");
    private static final String HEAD = "a".repeat(40);

    private final WorkspaceDslReadPort port = mock(WorkspaceDslReadPort.class);
    private final RepositoryRead read = mock(RepositoryRead.class);
    private final RelationBranchProjectionRebuildWriter writer = mock(RelationBranchProjectionRebuildWriter.class);

    private RelationBranchProjectionRebuildService rebuild() throws Exception {
        when(port.openRead(CONTEXT)).thenReturn(read);
        when(read.currentHead()).thenReturn(HEAD);
        return new RelationBranchProjectionRebuildService(port, writer);
    }

    @Test
    void missingDslCannotEraseAnExistingProjection() throws Exception {
        var service = rebuild();
        when(read.dslAtCommit(HEAD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rebuild(CONTEXT))
                .isInstanceOf(BranchProjectionSourceException.class)
                .hasMessage("Authoritative commit has no architecture.taxdsl: " + HEAD);
        verifyNoInteractions(writer);
        verify(read, never()).verifyExpectedHead(any());
    }

    @Test
    void validEmptyDslReplacesRowsOnlyAfterTheCapturedHeadIsVerified() throws Exception {
        var service = rebuild();
        when(read.dslAtCommit(HEAD)).thenReturn(Optional.of(""));
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);

        service.rebuild(CONTEXT);

        var order = inOrder(port, read, writer);
        order.verify(port).openRead(CONTEXT);
        order.verify(read).currentHead();
        order.verify(read).dslAtCommit(HEAD);
        order.verify(read).verifyExpectedHead(HEAD);
        order.verify(writer).replace(CONTEXT, HEAD, List.of());
        order.verifyNoMoreInteractions();
    }

    @Test
    void concurrentHeadChangeStopsBeforeTheTransactionalWriter() throws Exception {
        var service = rebuild();
        when(read.dslAtCommit(HEAD)).thenReturn(Optional.of(""));
        var conflict = new IOException("branch moved");
        when(read.verifyExpectedHead(HEAD)).thenThrow(conflict);

        assertThatThrownBy(() -> service.rebuild(CONTEXT))
                .isInstanceOf(BranchProjectionSourceException.class)
                .hasMessage("Selected branch moved during relation projection rebuild")
                .hasCause(conflict);
        verifyNoInteractions(writer);
    }

    @Test
    void unexpectedVerifiedHeadIsNotAcceptedAsAuthority() throws Exception {
        var service = rebuild();
        when(read.dslAtCommit(HEAD)).thenReturn(Optional.of(""));
        when(read.verifyExpectedHead(HEAD)).thenReturn("b".repeat(40));

        assertThatThrownBy(() -> service.rebuild(CONTEXT))
                .isInstanceOf(BranchProjectionSourceException.class)
                .hasMessage("Selected branch no longer has the captured projection head");
        verifyNoInteractions(writer);
    }

    @Test
    void missingBranchAndStorageErrorsCannotReachTheWriter() throws Exception {
        var service = rebuild();
        when(read.currentHead()).thenReturn(null);
        assertThatThrownBy(() -> service.rebuild(CONTEXT))
                .isInstanceOf(BranchProjectionSourceException.class)
                .hasMessageContaining("branch 'review' does not exist");
        when(read.currentHead()).thenThrow(new IOException("unavailable"));
        assertThatThrownBy(() -> service.rebuild(CONTEXT))
                .isInstanceOf(BranchProjectionSourceException.class)
                .hasMessage("Unable to read relation projection branch head 'review'");
        verifyNoInteractions(writer);
        verify(read, never()).dslAtCommit(any());
    }

    @Test
    void unreadableCommitIsNotTreatedAsAnEmptyArchitecture() throws Exception {
        var service = rebuild();
        when(read.dslAtCommit(HEAD)).thenThrow(new IOException("unreadable"));
        assertThatThrownBy(() -> service.rebuild(CONTEXT))
                .isInstanceOf(BranchProjectionSourceException.class)
                .hasMessage("Unable to read authoritative relation projection commit " + HEAD);
        verifyNoInteractions(writer);
    }

    @Test
    void readinessRechecksTheSameBoundBranchAfterLoadingRows() throws Exception {
        when(port.openRead(CONTEXT)).thenReturn(read);
        when(read.currentHead()).thenReturn(HEAD, "b".repeat(40));
        var projections = mock(RelationDecisionProjectionRepository.class);
        var checkpoints = mock(RelationDecisionProjectionCheckpointRepository.class);
        var checkpoint = new RelationDecisionProjectionCheckpoint();
        checkpoint.setAuthoritativeCommitId(HEAD);
        checkpoint.setRelationCount(0);
        when(checkpoints.findByRepositoryIdAndWorkspaceScopeKeyAndBranch(
                "repo-a", "workspace-a", "review")).thenReturn(Optional.of(checkpoint));
        when(projections.findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderBySourceCodeAscRelationTypeAscTargetCodeAsc(
                "repo-a", "workspace-a", "review")).thenReturn(List.of());

        var result = new RelationBranchProjectionReadinessService(port, projections, checkpoints)
                .inspect(CONTEXT);

        assertThat(result.state()).isEqualTo(ReadinessState.STALE);
        assertThat(result.currentHeadCommit()).isEqualTo("b".repeat(40));
        assertThat(result.rows()).isEmpty();
        verify(port).openRead(CONTEXT);
        verify(read, times(2)).currentHead();
    }

    @Test
    void readinessStorageErrorsRetainTheirPublicExceptionAndSkipDatabaseAccess() throws Exception {
        when(port.openRead(CONTEXT)).thenReturn(read);
        when(read.currentHead()).thenThrow(new IOException("unavailable"));
        var projections = mock(RelationDecisionProjectionRepository.class);
        var checkpoints = mock(RelationDecisionProjectionCheckpointRepository.class);
        var service = new RelationBranchProjectionReadinessService(port, projections, checkpoints);

        assertThatThrownBy(() -> service.inspect(CONTEXT))
                .isInstanceOf(RelationProjectionNotReadyException.class)
                .hasMessage("Unable to read selected relation projection branch head");
        verifyNoInteractions(projections, checkpoints);
    }
}
