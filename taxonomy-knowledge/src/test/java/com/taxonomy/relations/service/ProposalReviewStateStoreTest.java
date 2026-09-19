package com.taxonomy.relations.service;

import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalReviewStateStoreTest {

    private final RelationProposalRepository repository =
            mock(RelationProposalRepository.class);
    private final ProposalReviewStateStore store =
            new ProposalReviewStateStore(repository);
    private final RepositoryContext context = new RepositoryContext(
            "repo-a",
            "workspace-a",
            "review",
            "alice",
            RepositoryScope.WORKSPACE);

    @Test
    void transitionLocksTheExactTenantRowAndRechecksItsStatus() {
        RelationProposal proposal = proposal(ProposalStatus.PENDING);
        when(repository.findByIdInRepositoryWorkspaceForUpdate(
                "repo-a", 17L, "workspace-a"))
                .thenReturn(Optional.of(proposal));

        ProposalStatus result = store.transition(
                17L,
                context,
                ProposalStatus.PENDING,
                ProposalStatus.ACCEPTED);

        assertThat(result).isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
        assertThat(proposal.getReviewedAt()).isNotNull();
        verify(repository).save(proposal);
        verify(repository, never()).findByIdInRepositoryWorkspace(
                "repo-a", 17L, "workspace-a");
    }

    @Test
    void transitionRejectsAConcurrentProposalStatusChange() {
        RelationProposal proposal = proposal(ProposalStatus.REJECTED);
        when(repository.findByIdInRepositoryWorkspaceForUpdate(
                "repo-a", 17L, "workspace-a"))
                .thenReturn(Optional.of(proposal));

        assertThatThrownBy(() -> store.transition(
                17L,
                context,
                ProposalStatus.PENDING,
                ProposalStatus.ACCEPTED))
                .isInstanceOf(
                        ProposalReviewStateStore.ProposalStateConflictException.class)
                .hasMessageContaining("is REJECTED, expected PENDING");
        verify(repository, never()).save(proposal);
    }

    @Test
    void revertingToPendingClearsTheReviewTimestamp() {
        RelationProposal proposal = proposal(ProposalStatus.ACCEPTED);
        proposal.setReviewedAt(java.time.Instant.parse(
                "2026-08-12T10:00:00Z"));
        when(repository.findByIdInRepositoryWorkspaceForUpdate(
                "repo-a", 17L, "workspace-a"))
                .thenReturn(Optional.of(proposal));

        store.transition(
                17L,
                context,
                ProposalStatus.ACCEPTED,
                ProposalStatus.PENDING);

        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(proposal.getReviewedAt()).isNull();
    }

    private static RelationProposal proposal(ProposalStatus status) {
        RelationProposal proposal = new RelationProposal();
        proposal.setId(17L);
        proposal.setRepositoryId("repo-a");
        proposal.setWorkspaceId("workspace-a");
        proposal.setStatus(status);
        return proposal;
    }
}
