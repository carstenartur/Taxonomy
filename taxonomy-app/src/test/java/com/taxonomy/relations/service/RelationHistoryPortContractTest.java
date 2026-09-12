package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.relations.model.RelationProjectionRecovery;
import com.taxonomy.relations.model.RelationProjectionRecovery.RecoveryStatus;
import com.taxonomy.relations.repository.RelationProjectionRecoveryRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitMetadata;
import com.taxonomy.workspace.service.WorkspaceDslReadPort.RepositoryRead;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RelationHistoryPortContractTest {
    private static final String HEAD = "a".repeat(40);
    private static final String PREVIOUS = "b".repeat(40);
    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");
    private static final RemoveRelation REMOVE = new RemoveRelation(
            new RelationIdentity("APP-1", "USES", "SVC-1"), new CommandMetadata("decision-17"));
    private static final String SUMMARY = "relation: removed APP-1 USES SVC-1";
    private static final String MESSAGE = SUMMARY + "\n\nCausation-Id: decision-17";

    @Test
    void rejectsForgedSummaryActorCausationAndParentsBeforeDslOrDatabaseAccess() throws Exception {
        record Case(CommitMetadata metadata, String previous, String error) { }
        for (var scenario : List.of(
                new Case(new CommitMetadata("Wrong", "alice", MESSAGE, List.of(PREVIOUS)), PREVIOUS, "summary"),
                new Case(new CommitMetadata(SUMMARY, "bob", MESSAGE, List.of(PREVIOUS)), PREVIOUS, "author"),
                new Case(new CommitMetadata(SUMMARY, "alice", SUMMARY, List.of(PREVIOUS)), PREVIOUS, "causation"),
                new Case(new CommitMetadata(SUMMARY, "alice", MESSAGE, List.of()), PREVIOUS, "parent"),
                new Case(new CommitMetadata(SUMMARY, "alice", MESSAGE, List.of(HEAD)), PREVIOUS, "parent"),
                new Case(new CommitMetadata(SUMMARY, "alice", MESSAGE, List.of(PREVIOUS, HEAD)), PREVIOUS, "parent"),
                new Case(new CommitMetadata(SUMMARY, "alice", MESSAGE, List.of(PREVIOUS)), null, "parent"))) {
            var read = mock(RepositoryRead.class);
            when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
            when(read.commitMetadata(HEAD)).thenReturn(scenario.metadata());
            var writer = mock(RelationDecisionProjectionWriter.class);
            var service = new RelationDecisionProjectionService(writer, context -> read);

            assertThatThrownBy(() -> service.project(CONTEXT, authority(scenario.previous(), true), REMOVE))
                    .isInstanceOf(RelationDecisionProjectionService.ProjectionSourceException.class)
                    .hasMessageContaining(scenario.error());
            verify(read, never()).dslAtCommit(any());
            verifyNoInteractions(writer);
        }
    }

    @Test
    void exactHeadAndMetadataAreVerifiedBeforeReadingAndWritingTheProjection() throws Exception {
        var reads = mock(WorkspaceDslReadPort.class);
        var read = mock(RepositoryRead.class);
        when(reads.openRead(CONTEXT)).thenReturn(read);
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
        when(read.commitMetadata(HEAD)).thenReturn(new CommitMetadata(SUMMARY, "alice", MESSAGE, List.of(PREVIOUS)));
        when(read.dslAtCommit(HEAD)).thenReturn(Optional.of(""));
        var writer = mock(RelationDecisionProjectionWriter.class);
        var service = new RelationDecisionProjectionService(writer, reads);

        service.project(CONTEXT, authority(PREVIOUS, true), REMOVE);

        var order = inOrder(reads, read, writer);
        order.verify(reads).openRead(CONTEXT);
        order.verify(read).verifyExpectedHead(HEAD);
        order.verify(read).commitMetadata(HEAD);
        order.verify(read).dslAtCommit(HEAD);
        order.verify(writer).write(any());
        verify(reads, times(1)).openRead(CONTEXT);
    }

    @Test
    void metadataIoFailureCannotPublishAPartialProjection() throws Exception {
        var read = mock(RepositoryRead.class);
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
        var failure = new IOException("Unreadable commit");
        when(read.commitMetadata(HEAD)).thenThrow(failure);
        var writer = mock(RelationDecisionProjectionWriter.class);
        var service = new RelationDecisionProjectionService(writer, context -> read);

        assertThatThrownBy(() -> service.project(CONTEXT, authority(PREVIOUS, true), REMOVE))
                .isInstanceOf(RelationDecisionProjectionService.ProjectionSourceException.class)
                .hasMessageContaining("Unable to verify authoritative relation commit").hasCause(failure);
        verifyNoInteractions(writer);
    }

    @Test
    void semanticNoOpDoesNotDemandMetadataFromAnUnrelatedOriginalCommit() throws Exception {
        var read = mock(RepositoryRead.class);
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
        when(read.dslAtCommit(HEAD)).thenReturn(Optional.of(""));
        var writer = mock(RelationDecisionProjectionWriter.class);

        new RelationDecisionProjectionService(writer, context -> read)
                .project(CONTEXT, authority(HEAD, false), REMOVE);

        verify(read, never()).commitMetadata(any());
        verify(writer).write(any());
    }

    @Test
    void absentDslIsNotConfusedWithAValidEmptyRemovalDocument() throws Exception {
        var read = mock(RepositoryRead.class);
        when(read.verifyExpectedHead(HEAD)).thenReturn(HEAD);
        when(read.dslAtCommit(HEAD)).thenReturn(Optional.empty());
        var writer = mock(RelationDecisionProjectionWriter.class);

        assertThatThrownBy(() -> new RelationDecisionProjectionService(writer, context -> read)
                .project(CONTEXT, authority(HEAD, false), REMOVE))
                .isInstanceOf(RelationDecisionProjectionService.ProjectionSourceException.class)
                .hasMessageContaining("Authoritative relation commit is missing");
        verifyNoInteractions(writer);
    }

    @Test
    void recoveryCompletesOnlyExactAndProvenAncestorCommits() throws Exception {
        var pending = List.of(pending(PREVIOUS), pending(HEAD), pending("c".repeat(40)), pending("d".repeat(40)));
        var repository = recoveryRepository(pending);
        var read = mock(RepositoryRead.class);
        when(read.relationshipsTo(HEAD, pending.stream().map(RelationProjectionRecovery::getAuthoritativeCommitId).toList()))
                .thenReturn(List.of(ANCESTOR, SAME, UNRELATED, UNAVAILABLE));

        var result = new RelationProjectionRecoveryService(repository, context -> read)
                .reconcileAfterRebuild(CONTEXT, HEAD);

        assertThat(pending).extracting(RelationProjectionRecovery::getStatus)
                .containsExactly(RecoveryStatus.SUPERSEDED, RecoveryStatus.RECOVERED,
                        RecoveryStatus.PENDING, RecoveryStatus.PENDING);
        assertThat(result.recoveredCount()).isEqualTo(1);
        assertThat(result.supersededCount()).isEqualTo(1);
        assertThat(result.remainingPendingCount()).isEqualTo(2);
        verify(repository, times(2)).save(any(RelationProjectionRecovery.class));
    }

    @Test
    void incompleteOrUnreadableAncestryEvidenceNeverCompletesRows() throws Exception {
        for (boolean incomplete : List.of(true, false)) {
            var pending = List.of(pending(PREVIOUS), pending(HEAD));
            var repository = recoveryRepository(pending);
            var read = mock(RepositoryRead.class);
            if (incomplete) {
                when(read.relationshipsTo(HEAD, List.of(PREVIOUS, HEAD))).thenReturn(List.of(ANCESTOR));
            } else {
                when(read.relationshipsTo(HEAD, List.of(PREVIOUS, HEAD))).thenThrow(new IOException("Missing target"));
            }
            var service = new RelationProjectionRecoveryService(repository, context -> read);

            assertThatThrownBy(() -> service.reconcileAfterRebuild(CONTEXT, HEAD))
                    .isInstanceOf(RelationProjectionRecoveryService.ProjectionRecoveryReconciliationException.class)
                    .hasMessageContaining("Unable to reconcile").hasCauseInstanceOf(IOException.class);
            assertThat(pending).extracting(RelationProjectionRecovery::getStatus)
                    .containsOnly(RecoveryStatus.PENDING);
            verify(repository, never()).save(any(RelationProjectionRecovery.class));
        }
    }

    @Test
    void noPendingRecoveryDoesNotOpenGitHistory() {
        var reads = mock(WorkspaceDslReadPort.class);
        var result = new RelationProjectionRecoveryService(recoveryRepository(List.of()), reads)
                .reconcileAfterRebuild(CONTEXT, HEAD);
        assertThat(result.remainingPendingCount()).isZero();
        verifyNoInteractions(reads);
    }

    @Test
    void invalidRecoveryContextOrCommitIsRejectedBeforeDatabaseAndGitReads() {
        var repository = mock(RelationProjectionRecoveryRepository.class);
        var reads = mock(WorkspaceDslReadPort.class);
        var service = new RelationProjectionRecoveryService(repository, reads);
        assertThatThrownBy(() -> service.reconcileAfterRebuild(
                RepositoryContext.centralRead("repo-a", "review", "reader"), HEAD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("writable context");
        assertThatThrownBy(() -> service.reconcileAfterRebuild(CONTEXT, "review"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reconcileAfterRebuild(CONTEXT, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be blank");
        verifyNoInteractions(repository, reads);
    }

    private static RelationProjectionRecoveryRepository recoveryRepository(List<RelationProjectionRecovery> pending) {
        var repository = mock(RelationProjectionRecoveryRepository.class);
        when(repository.findStatusForUpdate("repo-a", "workspace-a", "review", RecoveryStatus.PENDING))
                .thenReturn(pending);
        return repository;
    }

    private static RelationProjectionRecovery pending(String commit) {
        var row = new RelationProjectionRecovery();
        row.setRepositoryId("repo-a");
        row.setWorkspaceId("workspace-a");
        row.setBranch("review");
        row.setAuthoritativeCommitId(commit);
        row.setCausationId("decision-17");
        row.recordFailure(new IOException("Awaiting projection"));
        return row;
    }

    private static CommandResult authority(String previous, boolean changed) {
        return new CommandResult("repo-a", "workspace-a", "review", CONTEXT.scope(),
                previous, HEAD, changed ? ChangeKind.REMOVED : ChangeKind.UNCHANGED, changed, "decision-17");
    }
}
