package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationProposalRepositoryScopeTest {

    private TaxonomyNodeRepository nodeRepository;
    private RelationProposalRepository proposalRepository;
    private RelationCandidateService candidateService;
    private RelationValidationService validationService;
    private WorkspaceResolver workspaceResolver;
    private RelationProposalService service;

    @BeforeEach
    void setUp() {
        nodeRepository = mock(TaxonomyNodeRepository.class);
        proposalRepository = mock(RelationProposalRepository.class);
        candidateService = mock(RelationCandidateService.class);
        validationService = mock(RelationValidationService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        service = new RelationProposalService(
                nodeRepository,
                proposalRepository,
                candidateService,
                validationService,
                workspaceResolver);
    }

    @Test
    void centralReadUsesOnlySelectedRepository() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "alice");
        when(proposalRepository.findCentralByRepository("repo-a"))
                .thenReturn(List.of());

        assertThat(service.getAllProposalsInContext(context)).isEmpty();

        verify(proposalRepository).findCentralByRepository("repo-a");
        verify(proposalRepository, never()).findAll();
    }

    @Test
    void workspaceReadInheritsOnlySelectedRepositoryBaseline() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(proposalRepository.findVisibleByRepositoryAndWorkspaceAndStatus(
                        "repo-a", "workspace-a", ProposalStatus.PENDING))
                .thenReturn(List.of());

        assertThat(service.getPendingProposalsInContext(context)).isEmpty();

        verify(proposalRepository).findVisibleByRepositoryAndWorkspaceAndStatus(
                "repo-a", "workspace-a", ProposalStatus.PENDING);
        verify(proposalRepository, never()).findByStatusAndWorkspace(any(), any());
    }

    @Test
    void centralReadContextCannotCreateProposal() {
        RepositoryContext context = RepositoryContext.centralRead(
                "repo-a", "main", "alice");

        assertThatIllegalArgumentException().isThrownBy(() ->
                service.createFromHypothesisInContext(
                        "BP", "CP", RelationType.SUPPORTS, 0.5, "reason", context))
                .withMessageContaining("CENTRAL_WRITE");

        verify(nodeRepository, never()).findByCode(any());
        verify(proposalRepository, never()).save(any());
    }

    @Test
    void createFromHypothesisPersistsRepositoryWorkspaceAndOwnerFromContext() {
        TaxonomyNode source = node("BP", "Business Process");
        TaxonomyNode target = node("CP", "Capability");
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        when(nodeRepository.findByCode("BP")).thenReturn(Optional.of(source));
        when(nodeRepository.findByCode("CP")).thenReturn(Optional.of(target));
        when(proposalRepository.existsInRepositoryWorkspace(
                        "repo-a", "BP", "CP", RelationType.SUPPORTS, "workspace-a"))
                .thenReturn(false);
        when(proposalRepository.save(any(RelationProposal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createFromHypothesisInContext(
                "BP",
                "CP",
                RelationType.SUPPORTS,
                0.75,
                "reason",
                context);

        ArgumentCaptor<RelationProposal> captor =
                ArgumentCaptor.forClass(RelationProposal.class);
        verify(proposalRepository).save(captor.capture());
        RelationProposal saved = captor.getValue();
        assertThat(saved.getRepositoryId()).isEqualTo("repo-a");
        assertThat(saved.getWorkspaceId()).isEqualTo("workspace-a");
        assertThat(saved.getWorkspaceScopeKey()).isEqualTo("workspace-a");
        assertThat(saved.getOwnerUsername()).isEqualTo("alice");
    }

    @Test
    void duplicateCheckCannotSeeEquivalentProposalFromAnotherRepository() {
        RepositoryContext context = new RepositoryContext(
                "repo-a", null, "main", "alice", RepositoryScope.CENTRAL_WRITE);
        when(nodeRepository.findByCode("BP")).thenReturn(Optional.of(node("BP", "BP")));
        when(nodeRepository.findByCode("CP")).thenReturn(Optional.of(node("CP", "CP")));
        when(proposalRepository.existsInRepositoryWorkspace(
                        "repo-a", "BP", "CP", RelationType.SUPPORTS, null))
                .thenReturn(false);
        when(proposalRepository.save(any(RelationProposal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.createFromHypothesisInContext(
                "BP", "CP", RelationType.SUPPORTS, 0.5, "reason", context))
                .isNotNull();

        verify(proposalRepository).existsInRepositoryWorkspace(
                "repo-a", "BP", "CP", RelationType.SUPPORTS, null);
        verify(proposalRepository, never()).existsInWorkspace(any(), any(), any(), any());
    }

    @Test
    void entityRejectsMissingRepositoryIdentity() {
        RelationProposal proposal = new RelationProposal();

        assertThatIllegalArgumentException().isThrownBy(() ->
                proposal.setRepositoryId(" "));
    }

    private static TaxonomyNode node(String code, String name) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setNameEn(name);
        return node;
    }
}
