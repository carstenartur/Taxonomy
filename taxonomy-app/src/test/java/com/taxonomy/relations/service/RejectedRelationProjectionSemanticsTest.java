package com.taxonomy.relations.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RelationSnapshot;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionRequest;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RejectedRelationProjectionSemanticsTest {

    private static final String COMMIT = "a".repeat(40);

    @Test
    void onlyRejectedDecisionsAreInactiveForCompatibility() {
        assertThat(RelationDecisionStatusPolicy.isRelationPresent(null)).isTrue();
        assertThat(RelationDecisionStatusPolicy.isRelationPresent("accepted")).isTrue();
        assertThat(RelationDecisionStatusPolicy.isRelationPresent("PROPOSED")).isTrue();
        assertThat(RelationDecisionStatusPolicy.isRelationPresent(" provisional ")).isTrue();
        assertThat(RelationDecisionStatusPolicy.isRelationPresent(" rejected ")).isFalse();
    }

    @Test
    void incrementalRejectedUpsertBecomesAnExplicitInactiveTombstone() {
        RelationDecisionProjectionRepository repository =
                mock(RelationDecisionProjectionRepository.class);
        when(repository.findExactForUpdate(
                "repo-a",
                "workspace-a",
                "review",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.empty());
        ProjectionRequest request = new ProjectionRequest(
                "repo-a",
                "workspace-a",
                "review",
                "APP-1",
                RelationType.USES,
                "SVC-1",
                true,
                "rejected",
                0.8,
                "human-review",
                COMMIT,
                "proposal-17");

        var result = new RelationDecisionProjectionWriter(repository)
                .write(request);

        ArgumentCaptor<RelationDecisionProjection> saved =
                ArgumentCaptor.forClass(RelationDecisionProjection.class);
        verify(repository).save(saved.capture());
        assertThat(result.relationPresent()).isFalse();
        assertThat(saved.getValue().isRelationPresent()).isFalse();
        assertThat(saved.getValue().getStatus()).isEqualTo("rejected");
        assertThat(saved.getValue().getAuthoritativeCommitId())
                .isEqualTo(COMMIT);
    }

    @Test
    void completeRebuildKeepsRejectedDecisionsInGitButNotInReadyRows() {
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        when(checkpoints.findExactForUpdate(
                "repo-a", "workspace-a", "review"))
                .thenReturn(Optional.empty());
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "review", "alice");
        List<RelationSnapshot> snapshots = List.of(
                new RelationSnapshot(
                        "APP-1", RelationType.USES, "SVC-1",
                        "accepted", 0.9, "human-review"),
                new RelationSnapshot(
                        "APP-2", RelationType.DEPENDS_ON, "SVC-2",
                        "rejected", 0.7, "human-review"));

        var result = new RelationBranchProjectionRebuildWriter(
                projections, checkpoints)
                .replace(context, COMMIT, snapshots);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Iterable> savedRows =
                ArgumentCaptor.forClass(Iterable.class);
        verify(projections).saveAll(savedRows.capture());
        List<RelationDecisionProjection> rows = StreamSupport.stream(
                        savedRows.getValue().spliterator(), false)
                .map(RelationDecisionProjection.class::cast)
                .toList();
        ArgumentCaptor<RelationDecisionProjectionCheckpoint> checkpoint =
                ArgumentCaptor.forClass(
                        RelationDecisionProjectionCheckpoint.class);
        verify(checkpoints).saveAndFlush(checkpoint.capture());

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSourceCode()).isEqualTo("APP-1");
        assertThat(rows.getFirst().isRelationPresent()).isTrue();
        assertThat(result.relationCount()).isEqualTo(1);
        assertThat(checkpoint.getValue().getRelationCount()).isEqualTo(1);
        assertThat(checkpoint.getValue().getAuthoritativeCommitId())
                .isEqualTo(COMMIT);
    }
}
