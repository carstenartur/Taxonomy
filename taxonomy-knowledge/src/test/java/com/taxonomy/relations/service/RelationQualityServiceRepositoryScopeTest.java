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
        var tally = mock(RelationProposalRepository.ReviewTally.class);
        when(tally.getAccepted()).thenReturn(3L);
        when(tally.getReviewed()).thenReturn(4L);
        when(repository.visibleReviewHistory("repo-b", "workspace-b1", "BP", "CP",
                RelationType.RELATED_TO, ProposalStatus.ACCEPTED, ProposalStatus.REJECTED)).thenReturn(tally);

        double weight = service.acceptanceHistoryWeight(
                "BP", "CP", RelationType.RELATED_TO, context);

        assertThat(weight).isEqualTo(0.75);
        verify(repository).visibleReviewHistory("repo-b", "workspace-b1", "BP", "CP",
                RelationType.RELATED_TO, ProposalStatus.ACCEPTED, ProposalStatus.REJECTED);
        verify(repository, never())
                .countBySourceNodeTaxonomyRootAndTargetNodeTaxonomyRootAndRelationTypeAndStatus(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void topRejectedQueriesOnlyRejectedRowsFromTheExactWorkspaceView() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "feature/a1", "alice");
        when(repository.findVisibleByRepositoryAndWorkspaceAndStatus(
                "repo-a", "workspace-a1", ProposalStatus.REJECTED))
                .thenReturn(List.of(
                        proposal(1L, ProposalStatus.REJECTED, 0.4,
                                RelationType.RELATED_TO, "manual", "A", "B"),
                        proposal(2L, ProposalStatus.REJECTED, 0.9,
                                RelationType.DEPENDS_ON, "manual", "A", "C")));

        var rejected = service.topRejected(1, context);

        assertThat(rejected).hasSize(1);
        assertThat(rejected.getFirst().targetCode()).isEqualTo("C");
        assertThat(rejected.getFirst().confidence()).isEqualTo(0.9);
        verify(repository).findVisibleByRepositoryAndWorkspaceAndStatus(
                "repo-a", "workspace-a1", ProposalStatus.REJECTED);
        verify(repository, never()).findVisibleByRepositoryAndWorkspace(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(repository, never()).findByStatusOrderByConfidenceDesc(
                ProposalStatus.REJECTED);
    }

    @Test
    void topRejectedQueriesOnlyRejectedRowsFromTheSelectedCentralRepository() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-b", "main", "bob");
        when(repository.findCentralByRepositoryAndStatus(
                "repo-b", ProposalStatus.REJECTED))
                .thenReturn(List.of(
                        proposal(4L, ProposalStatus.REJECTED, 0.7,
                                RelationType.RELATED_TO, "manual", "B", "C")));

        var rejected = service.topRejected(5, context);

        assertThat(rejected).singleElement()
                .satisfies(item -> assertThat(item.confidence()).isEqualTo(0.7));
        verify(repository).findCentralByRepositoryAndStatus(
                "repo-b", ProposalStatus.REJECTED);
        verify(repository, never()).findCentralByRepository(
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dashboardDerivesAllCountersAndOrderedExamplesFromOneVisibleRead() {
        var scope = RepositoryContext.workspace("repo-a", "workspace-a1", "feature", "alice");
        when(repository.findVisibleByRepositoryAndWorkspace("repo-a", "workspace-a1")).thenReturn(List.of(
                proposal(8L, ProposalStatus.REJECTED, 0.8, RelationType.DEPENDS_ON, "manual", "A", "eight"),
                proposal(7L, ProposalStatus.REJECTED, 0.8, RelationType.RELATED_TO, "manual", "A", "seven"),
                proposal(6L, ProposalStatus.ACCEPTED, 1.0, RelationType.RELATED_TO, "manual", "A", "accepted")));

        var dashboard = service.dashboard(1, scope);

        assertThat(dashboard.metrics().accepted()).isOne();
        assertThat(dashboard.metrics().rejected()).isEqualTo(2);
        assertThat(dashboard.metrics().byRelationType()).hasSize(2);
        assertThat(dashboard.topRejected()).singleElement().satisfies(item -> assertThat(item.targetCode()).isEqualTo("seven"));
        verify(repository).findVisibleByRepositoryAndWorkspace("repo-a", "workspace-a1");
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
        assertThat(service.dashboard(0, scope).topRejected()).isEmpty();
        assertThat(service.dashboard(-1, scope).topRejected()).isEmpty();
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
