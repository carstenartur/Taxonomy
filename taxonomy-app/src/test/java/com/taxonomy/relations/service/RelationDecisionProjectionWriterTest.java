package com.taxonomy.relations.service;

import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationDecisionProjection;
import com.taxonomy.relations.repository.RelationDecisionProjectionRepository;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionConflictException;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionOutcome;
import com.taxonomy.relations.service.RelationDecisionProjectionService.ProjectionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationDecisionProjectionWriterTest {

    @Test
    void createsAnExactRepositoryWorkspaceAndBranchProjection() {
        RelationDecisionProjectionRepository repository =
                mock(RelationDecisionProjectionRepository.class);
        ProjectionRequest request = request(
                "repo-a",
                "workspace-a",
                "review",
                true,
                "accepted",
                0.9,
                "manual",
                commit('a'),
                "proposal-17");
        when(repository.findExactForUpdate(
                "repo-a",
                "workspace-a",
                "review",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.empty());

        RelationDecisionProjectionWriter writer =
                new RelationDecisionProjectionWriter(repository);
        var result = writer.write(request);

        ArgumentCaptor<RelationDecisionProjection> saved =
                ArgumentCaptor.forClass(RelationDecisionProjection.class);
        verify(repository).save(saved.capture());
        RelationDecisionProjection projection = saved.getValue();
        assertThat(result.outcome()).isEqualTo(ProjectionOutcome.CREATED);
        assertThat(projection.getRepositoryId()).isEqualTo("repo-a");
        assertThat(projection.getWorkspaceId()).isEqualTo("workspace-a");
        assertThat(projection.getWorkspaceScopeKey())
                .isEqualTo("workspace-a");
        assertThat(projection.getBranch()).isEqualTo("review");
        assertThat(projection.getSourceCode()).isEqualTo("APP-1");
        assertThat(projection.getRelationType()).isEqualTo(RelationType.USES);
        assertThat(projection.getTargetCode()).isEqualTo("SVC-1");
        assertThat(projection.isRelationPresent()).isTrue();
        assertThat(projection.getStatus()).isEqualTo("accepted");
        assertThat(projection.getConfidence()).isEqualTo(0.9);
        assertThat(projection.getProvenance()).isEqualTo("manual");
        assertThat(projection.getAuthoritativeCommitId())
                .isEqualTo(commit('a'));
        assertThat(projection.getCausationId()).isEqualTo("proposal-17");
    }

    @Test
    void replayingTheSameCommitAndStateDoesNotWriteAgain() {
        RelationDecisionProjectionRepository repository =
                mock(RelationDecisionProjectionRepository.class);
        ProjectionRequest request = request(
                "repo-a",
                null,
                "accepted",
                true,
                "accepted",
                0.9,
                "manual",
                commit('b'),
                "proposal-18");
        RelationDecisionProjection existing = projection(request);
        when(repository.findExactForUpdate(
                "repo-a",
                RelationDecisionProjection.CENTRAL_SCOPE_KEY,
                "accepted",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.of(existing));

        RelationDecisionProjectionWriter writer =
                new RelationDecisionProjectionWriter(repository);
        var result = writer.write(request);

        assertThat(result.outcome()).isEqualTo(ProjectionOutcome.REPLAYED);
        assertThat(result.authoritativeCommitId()).isEqualTo(commit('b'));
        verify(repository, never()).save(any());
    }

    @Test
    void sameCommitWithDifferentStateFailsClosed() {
        RelationDecisionProjectionRepository repository =
                mock(RelationDecisionProjectionRepository.class);
        ProjectionRequest request = request(
                "repo-a",
                "workspace-a",
                "draft",
                true,
                "accepted",
                0.9,
                "manual",
                commit('c'),
                "proposal-19");
        RelationDecisionProjection existing = projection(request);
        existing.setRelationPresent(false);
        existing.setStatus(null);
        existing.setConfidence(null);
        existing.setProvenance(null);
        when(repository.findExactForUpdate(
                "repo-a",
                "workspace-a",
                "draft",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.of(existing));

        RelationDecisionProjectionWriter writer =
                new RelationDecisionProjectionWriter(repository);

        assertThatThrownBy(() -> writer.write(request))
                .isInstanceOf(ProjectionConflictException.class)
                .hasMessageContaining(commit('c'));
        verify(repository, never()).save(any());
    }

    @Test
    void aNewCommitReplacesThePriorProjectionState() {
        RelationDecisionProjectionRepository repository =
                mock(RelationDecisionProjectionRepository.class);
        ProjectionRequest request = request(
                "repo-a",
                "workspace-a",
                "draft",
                true,
                "accepted",
                0.95,
                "manual",
                commit('d'),
                "proposal-20");
        RelationDecisionProjection existing = projection(request(
                "repo-a",
                "workspace-a",
                "draft",
                false,
                null,
                null,
                null,
                commit('e'),
                "remove-1"));
        when(repository.findExactForUpdate(
                "repo-a",
                "workspace-a",
                "draft",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.of(existing));

        RelationDecisionProjectionWriter writer =
                new RelationDecisionProjectionWriter(repository);
        var result = writer.write(request);

        assertThat(result.outcome()).isEqualTo(ProjectionOutcome.UPDATED);
        assertThat(existing.isRelationPresent()).isTrue();
        assertThat(existing.getStatus()).isEqualTo("accepted");
        assertThat(existing.getConfidence()).isEqualTo(0.95);
        assertThat(existing.getProvenance()).isEqualTo("manual");
        assertThat(existing.getAuthoritativeCommitId())
                .isEqualTo(commit('d'));
        assertThat(existing.getCausationId()).isEqualTo("proposal-20");
        verify(repository).save(existing);
    }

    @Test
    void removalIsStoredAsATombstoneInsteadOfDeletingTheProjection() {
        RelationDecisionProjectionRepository repository =
                mock(RelationDecisionProjectionRepository.class);
        ProjectionRequest request = request(
                "repo-a",
                "workspace-a",
                "variant/no-service",
                false,
                null,
                null,
                null,
                commit('f'),
                "remove-2");
        when(repository.findExactForUpdate(
                "repo-a",
                "workspace-a",
                "variant/no-service",
                "APP-1",
                RelationType.USES,
                "SVC-1"))
                .thenReturn(Optional.empty());

        RelationDecisionProjectionWriter writer =
                new RelationDecisionProjectionWriter(repository);
        writer.write(request);

        ArgumentCaptor<RelationDecisionProjection> saved =
                ArgumentCaptor.forClass(RelationDecisionProjection.class);
        verify(repository).save(saved.capture());
        RelationDecisionProjection tombstone = saved.getValue();
        assertThat(tombstone.isRelationPresent()).isFalse();
        assertThat(tombstone.getStatus()).isNull();
        assertThat(tombstone.getConfidence()).isNull();
        assertThat(tombstone.getProvenance()).isNull();
    }

    private static ProjectionRequest request(
            String repositoryId,
            String workspaceId,
            String branch,
            boolean present,
            String status,
            Double confidence,
            String provenance,
            String commitId,
            String causationId) {
        return new ProjectionRequest(
                repositoryId,
                workspaceId,
                branch,
                "APP-1",
                RelationType.USES,
                "SVC-1",
                present,
                status,
                confidence,
                provenance,
                commitId,
                causationId);
    }

    private static RelationDecisionProjection projection(
            ProjectionRequest request) {
        RelationDecisionProjection projection =
                new RelationDecisionProjection();
        projection.setRepositoryId(request.repositoryId());
        projection.setWorkspaceId(request.workspaceId());
        projection.setBranch(request.branch());
        projection.setSourceCode(request.sourceCode());
        projection.setRelationType(request.relationType());
        projection.setTargetCode(request.targetCode());
        projection.setRelationPresent(request.relationPresent());
        projection.setStatus(request.status());
        projection.setConfidence(request.confidence());
        projection.setProvenance(request.provenance());
        projection.setAuthoritativeCommitId(
                request.authoritativeCommitId());
        projection.setCausationId(request.causationId());
        return projection;
    }

    private static String commit(char value) {
        return String.valueOf(value).repeat(40);
    }
}
