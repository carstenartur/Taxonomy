package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalReviewRepository;
import com.taxonomy.relations.service.ProposalReviewProjectionWriter.ProjectionOutcome;
import com.taxonomy.relations.service.ProposalReviewProjectionWriter.ProjectionRequest;
import com.taxonomy.relations.service.ProposalReviewProjectionWriter.ProposalReviewConflictException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalReviewProjectionWriterTest {

    private static final Instant REVIEWED_AT =
            Instant.parse("2026-08-11T20:00:00Z");
    private static final String COMMIT = "a".repeat(40);

    @Test
    void projectsDecisionCommitAndCausationAfterLockingExactTenantProposal() {
        RelationProposalReviewRepository repository =
                mock(RelationProposalReviewRepository.class);
        RelationProposal proposal = pendingProposal();
        when(repository.findExactForUpdate("repo-a", "workspace-a", 17L))
                .thenReturn(Optional.of(proposal));
        ProposalReviewProjectionWriter writer = writer(repository);

        var result = writer.project(request(
                ProposalReviewDecision.ACCEPT, COMMIT, "proposal-17"));

        assertThat(result.outcome()).isEqualTo(ProjectionOutcome.UPDATED);
        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(proposal.getReviewedAt()).isEqualTo(REVIEWED_AT);
        assertThat(proposal.getReviewBranch()).isEqualTo("review");
        assertThat(proposal.getReviewCommitId()).isEqualTo(COMMIT);
        assertThat(proposal.getReviewCausationId()).isEqualTo("proposal-17");
        verify(repository).save(proposal);
        verify(repository).flush();
    }

    @Test
    void exactReplayIsIdempotent() {
        RelationProposalReviewRepository repository =
                mock(RelationProposalReviewRepository.class);
        RelationProposal proposal = pendingProposal();
        proposal.setStatus(ProposalStatus.REJECTED);
        proposal.setReviewedAt(REVIEWED_AT);
        proposal.setReviewBranch("review");
        proposal.setReviewCommitId(COMMIT);
        proposal.setReviewCausationId("proposal-17");
        when(repository.findExactForUpdate("repo-a", "workspace-a", 17L))
                .thenReturn(Optional.of(proposal));
        ProposalReviewProjectionWriter writer = writer(repository);

        var result = writer.project(request(
                ProposalReviewDecision.REJECT, COMMIT, "proposal-17"));

        assertThat(result.outcome()).isEqualTo(ProjectionOutcome.REPLAYED);
        verify(repository, never()).save(proposal);
        verify(repository, never()).flush();
    }

    @Test
    void conflictingReviewCannotOverwriteExistingAuthority() {
        RelationProposalReviewRepository repository =
                mock(RelationProposalReviewRepository.class);
        RelationProposal proposal = pendingProposal();
        proposal.setStatus(ProposalStatus.ACCEPTED);
        proposal.setReviewedAt(REVIEWED_AT);
        proposal.setReviewBranch("review");
        proposal.setReviewCommitId(COMMIT);
        proposal.setReviewCausationId("proposal-17");
        when(repository.findExactForUpdate("repo-a", "workspace-a", 17L))
                .thenReturn(Optional.of(proposal));
        ProposalReviewProjectionWriter writer = writer(repository);

        assertThatThrownBy(() -> writer.project(request(
                ProposalReviewDecision.REJECT,
                "b".repeat(40),
                "proposal-18")))
                .isInstanceOf(ProposalReviewConflictException.class)
                .hasMessageContaining("already reviewed");
        verify(repository, never()).save(proposal);
    }

    private static ProposalReviewProjectionWriter writer(
            RelationProposalReviewRepository repository) {
        return new ProposalReviewProjectionWriter(
                repository,
                Clock.fixed(REVIEWED_AT, ZoneOffset.UTC));
    }

    private static ProjectionRequest request(
            ProposalReviewDecision decision,
            String commit,
            String causationId) {
        return new ProjectionRequest(
                17L,
                "repo-a",
                "workspace-a",
                "review",
                "APP-1",
                "SVC-1",
                RelationType.USES,
                decision,
                commit,
                causationId);
    }

    private static RelationProposal pendingProposal() {
        TaxonomyNode source = new TaxonomyNode();
        source.setCode("APP-1");
        TaxonomyNode target = new TaxonomyNode();
        target.setCode("SVC-1");
        RelationProposal proposal = new RelationProposal();
        proposal.setId(17L);
        proposal.setRepositoryId("repo-a");
        proposal.setWorkspaceId("workspace-a");
        proposal.setSourceNode(source);
        proposal.setTargetNode(target);
        proposal.setRelationType(RelationType.USES);
        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setConfidence(0.9);
        return proposal;
    }
}
