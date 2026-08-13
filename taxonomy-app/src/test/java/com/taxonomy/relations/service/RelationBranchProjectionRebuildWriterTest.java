package com.taxonomy.relations.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RelationSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationBranchProjectionRebuildWriterTest {

    @Test
    void replacesOnlyTheExactBranchAndWritesItsCheckpoint() {
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        String commit = "a".repeat(40);
        when(checkpoints.findExactForUpdate(
                "repo-a", "workspace-a", "review"))
                .thenReturn(Optional.empty());

        RelationBranchProjectionRebuildWriter writer =
                new RelationBranchProjectionRebuildWriter(
                        projections, checkpoints);
        var result = writer.replace(
                context,
                commit,
                List.of(
                        new RelationSnapshot(
                                "APP-1",
                                RelationType.USES,
                                "SVC-1",
                                "accepted",
                                0.9,
                                "manual"),
                        new RelationSnapshot(
                                "SVC-1",
                                RelationType.DEPENDS_ON,
                                "DB-1",
                                null,
                                null,
                                null)));

        verify(projections).deleteExactBranch(
                "repo-a", "workspace-a", "review");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RelationDecisionProjection>> replacements =
                ArgumentCaptor.forClass(List.class);
        verify(projections).saveAll(replacements.capture());
        assertThat(replacements.getValue()).hasSize(2);
        assertThat(replacements.getValue())
                .allSatisfy(row -> {
                    assertThat(row.getRepositoryId()).isEqualTo("repo-a");
                    assertThat(row.getWorkspaceId()).isEqualTo("workspace-a");
                    assertThat(row.getBranch()).isEqualTo("review");
                    assertThat(row.isRelationPresent()).isTrue();
                    assertThat(row.getAuthoritativeCommitId()).isEqualTo(commit);
                    assertThat(row.getCausationId())
                            .isEqualTo("rebuild:" + commit);
                });

        ArgumentCaptor<RelationDecisionProjectionCheckpoint> checkpoint =
                ArgumentCaptor.forClass(
                        RelationDecisionProjectionCheckpoint.class);
        verify(checkpoints).saveAndFlush(checkpoint.capture());
        assertThat(checkpoint.getValue().getRepositoryId())
                .isEqualTo("repo-a");
        assertThat(checkpoint.getValue().getWorkspaceScopeKey())
                .isEqualTo("workspace-a");
        assertThat(checkpoint.getValue().getBranch())
                .isEqualTo("review");
        assertThat(checkpoint.getValue().getAuthoritativeCommitId())
                .isEqualTo(commit);
        assertThat(checkpoint.getValue().getRelationCount()).isEqualTo(2);
        assertThat(result.authoritativeCommitId()).isEqualTo(commit);
        assertThat(result.relationCount()).isEqualTo(2);
    }

    @Test
    void updatesTheLockedExistingCheckpointInsteadOfCreatingAnotherIdentity() {
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        RepositoryContext context = RepositoryContext.centralWrite(
                "repo-a", "accepted", "maintainer");
        RelationDecisionProjectionCheckpoint existing =
                new RelationDecisionProjectionCheckpoint();
        existing.setRepositoryId("repo-a");
        existing.setBranch("accepted");
        existing.setAuthoritativeCommitId("b".repeat(40));
        existing.setRelationCount(4);
        when(checkpoints.findExactForUpdate(
                "repo-a",
                RelationDecisionProjection.CENTRAL_SCOPE_KEY,
                "accepted"))
                .thenReturn(Optional.of(existing));

        RelationBranchProjectionRebuildWriter writer =
                new RelationBranchProjectionRebuildWriter(
                        projections, checkpoints);
        writer.replace(context, "c".repeat(40), List.of());

        verify(checkpoints).saveAndFlush(existing);
        assertThat(existing.getAuthoritativeCommitId())
                .isEqualTo("c".repeat(40));
        assertThat(existing.getRelationCount()).isZero();
    }
}
