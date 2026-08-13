package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.model.RelationProjectionRecovery;
import com.taxonomy.relations.model.RelationProjectionRecovery.RecoveryStatus;
import com.taxonomy.relations.repository.RelationProjectionRecoveryRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationProjectionRecoveryServiceTest {

    private DslGitRepositoryFactory repositoryFactory;

    @AfterEach
    void closeRepositories() {
        if (repositoryFactory != null) {
            repositoryFactory.close();
        }
    }

    @Test
    void recordsTheImmutableAuthorityInAnIndependentRecoveryRow() {
        RelationProjectionRecoveryRepository repository =
                mock(RelationProjectionRecoveryRepository.class);
        repositoryFactory = new DslGitRepositoryFactory(null);
        CommandResult authority = authority("a".repeat(40), "b".repeat(40));
        IllegalStateException failure =
                new IllegalStateException("projection unavailable");
        when(repository.findAuthorityForUpdate(
                "repo-a", "workspace-a", "review", "b".repeat(40)))
                .thenReturn(Optional.empty());
        when(repository.save(any(RelationProjectionRecovery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var record = new RelationProjectionRecoveryService(
                repository, repositoryFactory).recordPending(authority, failure);

        ArgumentCaptor<RelationProjectionRecovery> saved =
                ArgumentCaptor.forClass(RelationProjectionRecovery.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRepositoryId()).isEqualTo("repo-a");
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo("workspace-a");
        assertThat(saved.getValue().getPreviousHeadCommit())
                .isEqualTo("a".repeat(40));
        assertThat(saved.getValue().getAuthoritativeCommitId())
                .isEqualTo("b".repeat(40));
        assertThat(saved.getValue().getStatus())
                .isEqualTo(RecoveryStatus.PENDING);
        assertThat(saved.getValue().getAttemptCount()).isEqualTo(1);
        assertThat(record.failureMessage()).isEqualTo("projection unavailable");
    }

    @Test
    void rebuildCompletesExactAuthorityAndSupersedesItsAncestor()
            throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        DslGitRepository git = repositoryFactory.resolveRepository(context);
        String first = git.commitDsl(
                "review",
                "element APP-1 type Application {\n}\n",
                "alice",
                "First authority");
        String second = git.commitDsl(
                "review",
                "element APP-2 type Application {\n}\n",
                "alice",
                "Second authority");
        RelationProjectionRecovery ancestor = pending(first, "request-1");
        RelationProjectionRecovery exact = pending(second, "request-2");
        RelationProjectionRecoveryRepository repository =
                mock(RelationProjectionRecoveryRepository.class);
        when(repository.findStatusForUpdate(
                "repo-a", "workspace-a", "review", RecoveryStatus.PENDING))
                .thenReturn(List.of(ancestor, exact));
        when(repository.save(any(RelationProjectionRecovery.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = new RelationProjectionRecoveryService(
                repository, repositoryFactory)
                .reconcileAfterRebuild(context, second);

        assertThat(ancestor.getStatus()).isEqualTo(RecoveryStatus.SUPERSEDED);
        assertThat(exact.getStatus()).isEqualTo(RecoveryStatus.RECOVERED);
        assertThat(result.recoveredCount()).isEqualTo(1);
        assertThat(result.supersededCount()).isEqualTo(1);
        assertThat(result.remainingPendingCount()).isZero();
        verify(repository, times(2)).save(any(RelationProjectionRecovery.class));
    }

    private static RelationProjectionRecovery pending(
            String authority,
            String causationId) {
        RelationProjectionRecovery recovery = new RelationProjectionRecovery();
        recovery.setRepositoryId("repo-a");
        recovery.setWorkspaceId("workspace-a");
        recovery.setBranch("review");
        recovery.setPreviousHeadCommit(null);
        recovery.setAuthoritativeCommitId(authority);
        recovery.setCausationId(causationId);
        recovery.recordFailure(new IllegalStateException("pending"));
        return recovery;
    }

    private static CommandResult authority(String previous, String authority) {
        return new CommandResult(
                "repo-a",
                "workspace-a",
                "review",
                RepositoryScope.WORKSPACE,
                previous,
                authority,
                ChangeKind.UPDATED,
                true,
                "request-17");
    }
}
