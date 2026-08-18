package com.taxonomy.versioning.service;

import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.repository.RelationEvidenceRepository;
import com.taxonomy.relations.repository.RelationHypothesisRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HypothesisMutationScopeTest {

    @Test
    void centralReadAnalysisFailsBeforeAnyPersistenceOrGitAccess() {
        RelationHypothesisRepository hypotheses =
                mock(RelationHypothesisRepository.class);
        RelationEvidenceRepository evidence =
                mock(RelationEvidenceRepository.class);
        TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
        TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
        DslGitRepositoryFactory repositories = mock(DslGitRepositoryFactory.class);
        HypothesisService service = new HypothesisService(
                hypotheses, evidence, relations, nodes, repositories);

        assertThatThrownBy(() -> service.persistFromAnalysis(
                List.of(mock(RelationHypothesisDto.class)),
                "analysis-1",
                RepositoryContext.centralRead(
                        "repo-a", "main", "alice")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit central write context");

        verifyNoInteractions(hypotheses, evidence, relations, nodes, repositories);
    }

    @Test
    void terminalStateGuardsAndProvisionalRejectionCoverEveryMutationBranch() {
        RelationHypothesisRepository hypotheses =
                mock(RelationHypothesisRepository.class);
        RelationEvidenceRepository evidence =
                mock(RelationEvidenceRepository.class);
        TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
        TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
        DslGitRepositoryFactory repositories = mock(DslGitRepositoryFactory.class);
        HypothesisService service = new HypothesisService(
                hypotheses, evidence, relations, nodes, repositories);
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "draft", "alice");

        RelationHypothesis acceptedForAccept = hypothesis(
                1L, HypothesisStatus.ACCEPTED);
        RelationHypothesis rejectedForAccept = hypothesis(
                2L, HypothesisStatus.REJECTED);
        RelationHypothesis acceptedForReject = hypothesis(
                3L, HypothesisStatus.ACCEPTED);
        RelationHypothesis rejectedForReject = hypothesis(
                4L, HypothesisStatus.REJECTED);
        RelationHypothesis provisional = hypothesis(
                5L, HypothesisStatus.PROVISIONAL);

        when(hypotheses.findByIdInRepositoryWorkspace(
                "repo-a", 1L, "workspace-a"))
                .thenReturn(Optional.of(acceptedForAccept));
        when(hypotheses.findByIdInRepositoryWorkspace(
                "repo-a", 2L, "workspace-a"))
                .thenReturn(Optional.of(rejectedForAccept));
        when(hypotheses.findByIdInRepositoryWorkspace(
                "repo-a", 3L, "workspace-a"))
                .thenReturn(Optional.of(acceptedForReject));
        when(hypotheses.findByIdInRepositoryWorkspace(
                "repo-a", 4L, "workspace-a"))
                .thenReturn(Optional.of(rejectedForReject));
        when(hypotheses.findByIdInRepositoryWorkspace(
                "repo-a", 5L, "workspace-a"))
                .thenReturn(Optional.of(provisional));
        when(hypotheses.save(provisional)).thenReturn(provisional);

        assertThatThrownBy(() -> service.accept(1L, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already ACCEPTED");
        assertThatThrownBy(() -> service.accept(2L, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already REJECTED");
        assertThatThrownBy(() -> service.reject(3L, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already ACCEPTED");
        assertThatThrownBy(() -> service.reject(4L, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already REJECTED");

        assertThat(service.reject(5L, context)).isSameAs(provisional);
        assertThat(provisional.getStatus()).isEqualTo(HypothesisStatus.REJECTED);
        verify(hypotheses).save(provisional);
        verifyNoInteractions(evidence, relations, nodes, repositories);
    }

    @Test
    void nullRepositoryContextFailsBeforeAnyRepositoryAccess() {
        RelationHypothesisRepository hypotheses =
                mock(RelationHypothesisRepository.class);
        RelationEvidenceRepository evidence =
                mock(RelationEvidenceRepository.class);
        TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
        TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
        DslGitRepositoryFactory repositories = mock(DslGitRepositoryFactory.class);
        HypothesisService service = new HypothesisService(
                hypotheses, evidence, relations, nodes, repositories);

        assertThatThrownBy(() -> service.findAll((RepositoryContext) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");

        verifyNoInteractions(hypotheses, evidence, relations, nodes, repositories);
    }

    private static RelationHypothesis hypothesis(
            Long id,
            HypothesisStatus status) {
        RelationHypothesis hypothesis = new RelationHypothesis();
        hypothesis.setId(id);
        hypothesis.setRepositoryId("repo-a");
        hypothesis.setSourceNodeId("BP");
        hypothesis.setTargetNodeId("CP");
        hypothesis.setRelationType(RelationType.REALIZES);
        hypothesis.setStatus(status);
        hypothesis.setWorkspaceId("workspace-a");
        hypothesis.setOwnerUsername("alice");
        return hypothesis;
    }
}
