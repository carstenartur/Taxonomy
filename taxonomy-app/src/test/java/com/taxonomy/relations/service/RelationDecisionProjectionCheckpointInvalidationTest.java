package com.taxonomy.relations.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.repository.RelationDecisionProjectionCheckpointRepository;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationDecisionProjectionCheckpointInvalidationTest {

    @Test
    void incrementalProjectionInvalidatesTheExactFullBranchCheckpointFirst() {
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        ProjectionRequest request = new ProjectionRequest(
                "repo-a",
                "workspace-a",
                "review",
                "APP-1",
                RelationType.USES,
                "SVC-1",
                true,
                "accepted",
                0.9,
                "manual",
                "a".repeat(40),
                "proposal-17");
        when(projections.findExactForUpdate(
                "repo-a",
                "workspace-a",
                "review",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.empty());

        RelationDecisionProjectionWriter writer =
                new RelationDecisionProjectionWriter(
                        projections, checkpoints);
        var result = writer.write(request);

        InOrder order = inOrder(checkpoints, projections);
        order.verify(checkpoints).deleteExact(
                "repo-a", "workspace-a", "review");
        order.verify(projections).findExactForUpdate(
                "repo-a",
                "workspace-a",
                "review",
                "APP-1",
                RelationType.USES,
                "SVC-1");
        verify(projections).save(org.mockito.ArgumentMatchers.any());
        assertThat(result.authoritativeCommitId()).isEqualTo("a".repeat(40));
    }

    @Test
    void centralIncrementalProjectionInvalidatesOnlyTheSelectedRepositoryBranch() {
        RelationDecisionProjectionRepository projections =
                mock(RelationDecisionProjectionRepository.class);
        RelationDecisionProjectionCheckpointRepository checkpoints =
                mock(RelationDecisionProjectionCheckpointRepository.class);
        ProjectionRequest request = new ProjectionRequest(
                "repo-b",
                null,
                "accepted",
                "APP-1",
                RelationType.USES,
                "SVC-1",
                false,
                null,
                null,
                null,
                "b".repeat(40),
                "remove-3");
        when(projections.findExactForUpdate(
                "repo-b",
                "__shared__",
                "accepted",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.empty());

        new RelationDecisionProjectionWriter(
                projections, checkpoints).write(request);

        verify(checkpoints).deleteExact(
                "repo-b", "__shared__", "accepted");
    }
}
