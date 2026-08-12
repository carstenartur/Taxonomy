package com.taxonomy.relations.service;

import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.RelationProjectionNotReadyException;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationBranchProjectionReadinessServiceTest {

    private DslGitRepositoryFactory repositoryFactory;

    @AfterEach
    void closeRepositories() {
        if (repositoryFactory != null) {
            repositoryFactory.close();
        }
    }

    @Test
    void returnsRowsOnlyWhenCheckpointCountAndLiveGitHeadMatch() throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        DslGitRepository git = repositoryFactory.resolveRepository(context);
        String head = git.commitDsl(
                "review",
                "relation APP-1 USES SVC-1 {\n  status: accepted;\n}\n",
                "alice",
                "Seed ready projection");
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        RelationDecisionProjectionCheckpoint checkpoint = checkpoint(
                context, head, 1);
        RelationDecisionProjection row = projection(context, head, true);
        when(checkpoints.findByRepositoryIdAndWorkspaceScopeKeyAndBranch(
                "repo-a", "workspace-a", "review"))
                .thenReturn(Optional.of(checkpoint));
        when(projections
                .findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderBySourceCodeAscTargetCodeAsc(
                        "repo-a", "workspace-a", "review"))
                .thenReturn(List.of(row));

        RelationBranchProjectionReadinessService service =
                new RelationBranchProjectionReadinessService(
                        repositoryFactory, projections, checkpoints);
        var readiness = service.inspect(context);

        assertThat(readiness.state()).isEqualTo(ReadinessState.READY);
        assertThat(readiness.currentHeadCommit()).isEqualTo(head);
        assertThat(readiness.projectedCommit()).isEqualTo(head);
        assertThat(readiness.rows()).containsExactly(row);
        assertThat(service.requireReady(context)).containsExactly(row);
    }

    @Test
    void staleCheckpointNeverExposesRows() throws Exception {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        DslGitRepository git = repositoryFactory.resolveRepository(context);
        String head = git.commitDsl(
                "review",
                "element APP-1 type Application {\n}\n",
                "alice",
                "Advance branch");
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        when(checkpoints.findByRepositoryIdAndWorkspaceScopeKeyAndBranch(
                "repo-a", "workspace-a", "review"))
                .thenReturn(Optional.of(checkpoint(
                        context, "a".repeat(40), 1)));

        RelationBranchProjectionReadinessService service =
                new RelationBranchProjectionReadinessService(
                        repositoryFactory, projections, checkpoints);
        var readiness = service.inspect(context);

        assertThat(readiness.state()).isEqualTo(ReadinessState.STALE);
        assertThat(readiness.currentHeadCommit()).isEqualTo(head);
        assertThat(readiness.rows()).isEmpty();
        verifyNoInteractions(projections);
        assertThatThrownBy(() -> service.requireReady(context))
                .isInstanceOf(RelationProjectionNotReadyException.class)
                .hasMessageContaining("STALE");
    }

    @Test
    void rowCountMismatchOrTombstoneMakesProjectionCorrupt() throws Exception {
        RepositoryContext context = RepositoryContext.centralWrite(
                "repo-a", "accepted", "maintainer");
        repositoryFactory = new DslGitRepositoryFactory(null);
        String head = repositoryFactory.resolveRepository(context).commitDsl(
                "accepted",
                "element APP-1 type Application {\n}\n",
                "maintainer",
                "Seed central branch");
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        when(checkpoints.findByRepositoryIdAndWorkspaceScopeKeyAndBranch(
                "repo-a", "__shared__", "accepted"))
                .thenReturn(Optional.of(checkpoint(context, head, 1)));
        when(projections
                .findByRepositoryIdAndWorkspaceScopeKeyAndBranchOrderBySourceCodeAscTargetCodeAsc(
                        "repo-a", "__shared__", "accepted"))
                .thenReturn(List.of(projection(context, head, false)));

        var readiness = new RelationBranchProjectionReadinessService(
                repositoryFactory, projections, checkpoints).inspect(context);

        assertThat(readiness.state()).isEqualTo(ReadinessState.CORRUPT);
        assertThat(readiness.rows()).isEmpty();
    }

    @Test
    void missingBranchShortCircuitsBeforeDatabaseAccess() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "missing", "alice");
        repositoryFactory = new DslGitRepositoryFactory(null);
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);

        var readiness = new RelationBranchProjectionReadinessService(
                repositoryFactory, projections, checkpoints).inspect(context);

        assertThat(readiness.state()).isEqualTo(ReadinessState.BRANCH_MISSING);
        assertThat(readiness.rows()).isEmpty();
        verifyNoInteractions(checkpoints, projections);
    }

    private static RelationDecisionProjectionCheckpoint checkpoint(
            RepositoryContext context,
            String commit,
            int count) {
        RelationDecisionProjectionCheckpoint checkpoint =
                new RelationDecisionProjectionCheckpoint();
        checkpoint.setRepositoryId(context.repositoryId());
        checkpoint.setWorkspaceId(context.workspaceId());
        checkpoint.setBranch(context.branch());
        checkpoint.setAuthoritativeCommitId(commit);
        checkpoint.setRelationCount(count);
        return checkpoint;
    }

    private static RelationDecisionProjection projection(
            RepositoryContext context,
            String commit,
            boolean present) {
        RelationDecisionProjection projection = new RelationDecisionProjection();
        projection.setRepositoryId(context.repositoryId());
        projection.setWorkspaceId(context.workspaceId());
        projection.setBranch(context.branch());
        projection.setSourceCode("APP-1");
        projection.setRelationType(RelationType.USES);
        projection.setTargetCode("SVC-1");
        projection.setRelationPresent(present);
        projection.setAuthoritativeCommitId(commit);
        projection.setCausationId("rebuild:" + commit);
        return projection;
    }
}
