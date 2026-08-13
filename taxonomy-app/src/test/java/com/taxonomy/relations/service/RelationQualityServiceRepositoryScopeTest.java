package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationQualityServiceRepositoryScopeTest {

    private RelationProposalRepository repository;
    private RelationQualityService service;

    @BeforeEach
    void setUp() {
        repository = mock(RelationProposalRepository.class);
        service = new RelationQualityService(repository);
    }

    @Test
    void centralMetricsReadOnlyCentralRowsFromTheSelectedRepository() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "alice");
        when(repository.findCentralByRepository("repo-a"))
                .thenReturn(List.of(
                        proposal(1L, ProposalStatus.ACCEPTED, 0.8,
                                RelationType.RELATED_TO, "manual", "A", "B"),
                        proposal(2L, ProposalStatus.REJECTED, 0.6,
                                RelationType.RELATED_TO, "manual", "A", "C"),
                        proposal(3L, ProposalStatus.PENDING, 0.7,
                                RelationType.DEPENDS_ON, "hybrid-search", "A", "D")));

        var metrics = service.calculateMetrics(context);

        assertThat(metrics.totalProposals()).isEqualTo(3);
        assertThat(metrics.accepted()).isEqualTo(1);
        assertThat(metrics.rejected()).isEqualTo(1);
        assertThat(metrics.pending()).isEqualTo(1);
        assertThat(metrics.acceptanceRate()).isEqualTo(0.5);
        assertThat(metrics.avgConfidenceAccepted()).isEqualTo(0.8);
        assertThat(metrics.avgConfidenceRejected()).isEqualTo(0.6);
        assertThat(metrics.byRelationType()).hasSize(2);
        assertThat(metrics.byProvenance()).hasSize(2);
        verify(repository).findCentralByRepository("repo-a");
        verify(repository, never()).findVisibleByRepositoryAndWorkspace(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).findAll();
    }

    @Test
    void workspaceMetricsUseCentralPlusThatExactWorkspaceSnapshot() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "feature/a1", "alice");
        when(repository.findVisibleByRepositoryAndWorkspace(
                "repo-a", "workspace-a1"))
                .thenReturn(List.of(
                        proposal(1L, ProposalStatus.ACCEPTED, 0.9,
                                RelationType.RELATED_TO, "central", "A", "B"),
                        proposal(2L, ProposalStatus.REJECTED, 0.7,
                                RelationType.RELATED_TO, "workspace-a1", "A", "C")));

        var metrics = service.calculateMetrics(context);

        assertThat(metrics.totalProposals()).isEqualTo(2);
        assertThat(metrics.acceptanceRate()).isEqualTo(0.5);
        verify(repository).findVisibleByRepositoryAndWorkspace(
                "repo-a", "workspace-a1");
        verify(repository, never()).findCentralByRepository("repo-a");
    }

    @Test
    void feedbackHistoryUsesRepositoryAndExactWorkspaceForBothStatuses() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-b", "workspace-b1", "feature/b1", "bob");
        when(repository.countVisibleReviewHistory(
                "repo-b", "workspace-b1", "BP", "CP",
                RelationType.RELATED_TO, ProposalStatus.ACCEPTED))
                .thenReturn(3L);
        when(repository.countVisibleReviewHistory(
                "repo-b", "workspace-b1", "BP", "CP",
                RelationType.RELATED_TO, ProposalStatus.REJECTED))
                .thenReturn(1L);

        double weight = service.acceptanceHistoryWeight(
                "BP", "CP", RelationType.RELATED_TO, context);

        assertThat(weight).isEqualTo(0.75);
        verify(repository).countVisibleReviewHistory(
                "repo-b", "workspace-b1", "BP", "CP",
                RelationType.RELATED_TO, ProposalStatus.ACCEPTED);
        verify(repository).countVisibleReviewHistory(
                "repo-b", "workspace-b1", "BP", "CP",
                RelationType.RELATED_TO, ProposalStatus.REJECTED);
        verify(repository, never())
                .countBySourceNodeTaxonomyRootAndTargetNodeTaxonomyRootAndRelationTypeAndStatus(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void topRejectedNeverReadsAnotherRepositoryOrSiblingWorkspace() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "feature/a1", "alice");
        when(repository.findVisibleByRepositoryAndWorkspace(
                "repo-a", "workspace-a1"))
                .thenReturn(List.of(
                        proposal(1L, ProposalStatus.REJECTED, 0.4,
                                RelationType.RELATED_TO, "manual", "A", "B"),
                        proposal(2L, ProposalStatus.REJECTED, 0.9,
                                RelationType.DEPENDS_ON, "manual", "A", "C"),
                        proposal(3L, ProposalStatus.ACCEPTED, 0.99,
                                RelationType.RELATED_TO, "manual", "A", "D")));

        var rejected = service.topRejected(1, context);

        assertThat(rejected).hasSize(1);
        assertThat(rejected.getFirst().targetCode()).isEqualTo("C");
        assertThat(rejected.getFirst().confidence()).isEqualTo(0.9);
        verify(repository).findVisibleByRepositoryAndWorkspace(
                "repo-a", "workspace-a1");
        verify(repository, never()).findByStatusOrderByConfidenceDesc(
                ProposalStatus.REJECTED);
    }

    private static RelationProposal proposal(
            long id,
            ProposalStatus status,
            double confidence,
            RelationType relationType,
            String provenance,
            String sourceCode,
            String targetCode) {
        RelationProposal proposal = new RelationProposal();
        proposal.setId(id);
        proposal.setRepositoryId("repo-a");
        proposal.setSourceNode(node(sourceCode));
        proposal.setTargetNode(node(targetCode));
        proposal.setRelationType(relationType);
        proposal.setStatus(status);
        proposal.setConfidence(confidence);
        proposal.setProvenance(provenance);
        proposal.setRationale("evidence");
        return proposal;
    }

    private static TaxonomyNode node(String code) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn("Node " + code);
        node.setTaxonomyRoot(code);
        return node;
    }
}
