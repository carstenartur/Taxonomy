package com.taxonomy.relations.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionRequest;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationDecisionProjectionWriterSemanticNoOpTest {

    @Test
    void sameCommitAndStateWithANewCommandIdIsAReplay() {
        RelationDecisionProjectionRepository repository =
                mock(RelationDecisionProjectionRepository.class);
        String commitId = "a".repeat(40);
        ProjectionRequest request = new ProjectionRequest(
                "repo-a",
                "workspace-a",
                "draft",
                "APP-1",
                RelationType.USES,
                "SVC-1",
                true,
                "accepted",
                0.9,
                "manual",
                commitId,
                "relation-create:2");
        RelationDecisionProjection rebuilt = rebuiltProjection(commitId);
        when(repository.findExactForUpdate(
                "repo-a",
                "workspace-a",
                "draft",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.of(rebuilt));

        RelationDecisionProjectionWriter writer =
                new RelationDecisionProjectionWriter(repository);
        var result = writer.write(request);

        assertThat(result.outcome()).isEqualTo(ProjectionOutcome.REPLAYED);
        assertThat(result.authoritativeCommitId()).isEqualTo(commitId);
        assertThat(rebuilt.getCausationId()).isEqualTo("rebuild:" + commitId);
        verify(repository, never()).save(any());
    }

    private static RelationDecisionProjection rebuiltProjection(String commitId) {
        RelationDecisionProjection projection =
                new RelationDecisionProjection();
        projection.setRepositoryId("repo-a");
        projection.setWorkspaceId("workspace-a");
        projection.setBranch("draft");
        projection.setSourceCode("APP-1");
        projection.setRelationType(RelationType.USES);
        projection.setTargetCode("SVC-1");
        projection.setRelationPresent(true);
        projection.setStatus("accepted");
        projection.setConfidence(0.9);
        projection.setProvenance("manual");
        projection.setAuthoritativeCommitId(commitId);
        projection.setCausationId("rebuild:" + commitId);
        return projection;
    }
}
