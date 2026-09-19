package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RelationReviewServiceStateTest {
    private final RelationProposalRepository proposals = mock(RelationProposalRepository.class);
    private final TaxonomyRelationService relations = mock(TaxonomyRelationService.class);
    private final RelationProposalService mapping = mock(RelationProposalService.class);
    private final RelationReviewService service = new RelationReviewService(proposals, relations, mapping);
    private final RepositoryContext context = RepositoryContext.workspace("repo-b", "workspace-b", "draft", "alice");

    @ParameterizedTest
    @EnumSource(value = ProposalStatus.class, names = {"ACCEPTED", "REJECTED"})
    void revertingReviewResetsStateAndOnlyDeletesAnAcceptedRelationInTheSameWorkspace(ProposalStatus status) {
        RelationProposal proposal = proposal(status);
        when(proposals.findByIdInRepositoryWorkspace("repo-b", 42L, "workspace-b"))
                .thenReturn(Optional.of(proposal));
        var dto = new RelationProposalDto();
        when(mapping.toDto(proposal)).thenReturn(dto);
        assertThat(service.revertProposal(42L, context)).isSameAs(dto);
        assertThat(proposal.getStatus()).isEqualTo(ProposalStatus.PENDING);
        assertThat(proposal.getReviewedAt()).isNull();
        verify(proposals).save(proposal);
        if (status == ProposalStatus.ACCEPTED) {
            verify(relations).deleteRelationBySourceTargetTypeInContext("BP", "CP", RelationType.SUPPORTS, context);
            verifyNoMoreInteractions(relations);
        } else {
            verifyNoInteractions(relations);
        }
    }

    @Test
    void alreadyPendingProposalCannotBeReverted() {
        when(proposals.findByIdInRepositoryWorkspace("repo-b", 42L, "workspace-b"))
                .thenReturn(Optional.of(proposal(ProposalStatus.PENDING)));
        assertThatThrownBy(() -> service.revertProposal(42L, context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already PENDING");
        verify(proposals, never()).save(any());
        verifyNoInteractions(relations, mapping);
    }

    @ParameterizedTest
    @EnumSource(value = ProposalStatus.class, names = {"ACCEPTED", "REJECTED"})
    void anExistingDecisionCannotBeAcceptedOrRejectedAgain(ProposalStatus status) {
        when(proposals.findByIdInRepositoryWorkspace("repo-b", 42L, "workspace-b"))
                .thenReturn(Optional.of(proposal(status)));
        assertThatThrownBy(() -> service.acceptProposal(42L, context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already " + status);
        assertThatThrownBy(() -> service.rejectProposal(42L, context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already " + status);
        verify(proposals, never()).save(any());
        verifyNoInteractions(relations, mapping);
    }

    @Test
    void unknownProposalCannotBeLookedUpOutsideTheSelectedWorkspace() {
        when(proposals.findByIdInRepositoryWorkspace("repo-b", 42L, "workspace-b"))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revertProposal(42L, context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("active repository/workspace");
        verify(proposals).findByIdInRepositoryWorkspace("repo-b", 42L, "workspace-b");
        verifyNoMoreInteractions(proposals);
        verifyNoInteractions(relations, mapping);
    }

    @Test
    void nullOrReadOnlyContextsAreRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.revertProposal(42L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be null");
        assertThatThrownBy(() -> service.revertProposal(42L,
                RepositoryContext.centralRead("repo-b", "main", "alice")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CENTRAL_WRITE");
        verifyNoInteractions(proposals, relations, mapping);
    }

    private static RelationProposal proposal(ProposalStatus status) {
        var source = new TaxonomyNode();
        source.setCode("BP");
        var target = new TaxonomyNode();
        target.setCode("CP");
        var result = new RelationProposal();
        result.setSourceNode(source);
        result.setTargetNode(target);
        result.setRelationType(RelationType.SUPPORTS);
        result.setStatus(status);
        result.setReviewedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return result;
    }
}
