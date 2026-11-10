package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelationProposalLegalityTest {
    private final TaxonomyNodeRepository nodes = mock(TaxonomyNodeRepository.class);
    private final RelationProposalRepository proposals = mock(RelationProposalRepository.class);
    private final RelationCandidateService candidates = mock(RelationCandidateService.class);
    private final RelationProjectionReadService relations = mock(RelationProjectionReadService.class);
    private final RelationValidationService validation = mock(RelationValidationService.class);
    private final RelationProposalService service = new RelationProposalService(nodes, proposals, candidates, relations, validation, mock(WorkspaceResolver.class));
    private final RepositoryContext context = RepositoryContext.centralWrite("repo", "main", "user");
    private void node(String id, String root) {
        var node = new TaxonomyNode(); node.setCode(id); node.setTaxonomyRoot(root);
        when(nodes.findByCode(id)).thenReturn(Optional.of(node));
    }
    @Test void forbiddenSourceShortCircuitsBeforeProjectionSearchAndModelValidation() {
        node("info", "IP");
        when(relations.readIdentitySnapshot(context)).thenReturn(new RelationProjectionReadService.IdentitySnapshot(
                RelationProjectionReadService.ReadModel.PROJECTION, RelationBranchProjectionReadinessService.ReadinessState.READY,
                "a".repeat(40), java.util.Set.of()));
        assertThat(service.proposeRelationsInContext("info", RelationType.REALIZES, 5, context)).isEmpty();
        verifyNoInteractions(relations, candidates, validation, proposals);
    }
    @Test void incompatibleHypothesisFailsBeforeReadingOrPersistingProposalEvidence() {
        node("info", "IP"); node("service", "CR");
        when(relations.readIdentitySnapshot(context)).thenReturn(new RelationProjectionReadService.IdentitySnapshot(
                RelationProjectionReadService.ReadModel.PROJECTION, RelationBranchProjectionReadinessService.ReadinessState.READY,
                "a".repeat(40), java.util.Set.of()));
        when(proposals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThatThrownBy(() -> service.createFromHypothesisInContext("info", "service", RelationType.REALIZES, 0.99, "fixture", context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("incompatible");
        verifyNoInteractions(relations, proposals, validation, candidates);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5})
    void readOnlyContextIsRejectedBeforeRequestCanSelectANoOp(int limit) {
        var readOnly = RepositoryContext.centralRead("repo", "main", "user");

        assertThatThrownBy(() -> service.proposeRelationsInContext(
                "info", RelationType.REALIZES, limit, readOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CENTRAL_WRITE");

        verifyNoInteractions(nodes, relations, candidates, validation, proposals);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void authorizedNoOpDoesNotReadOrCreateRelationEvidence(int limit) {
        node("service", "CR");

        assertThat(service.proposeRelationsInContext(
                "service", RelationType.SUPPORTS, limit, context)).isEmpty();

        verifyNoInteractions(relations, candidates, validation, proposals);
    }
}
