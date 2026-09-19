package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.dto.TaxonomyNodeDto;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationProjectionReadService.IdentitySnapshot;
import com.taxonomy.relations.service.RelationProjectionReadService.ReadModel;
import com.taxonomy.relations.service.RelationProjectionReadService.RelationIdentity;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelationProposalProjectionDuplicateTest {

    private TaxonomyNodeRepository nodeRepository;
    private RelationProposalRepository proposalRepository;
    private RelationCandidateService candidateService;
    private RelationProjectionReadService relationReadService;
    private RelationValidationService validationService;
    private WorkspaceResolver workspaceResolver;
    private RelationProposalService service;

    @BeforeEach
    void setUp() {
        nodeRepository = mock(TaxonomyNodeRepository.class);
        proposalRepository = mock(RelationProposalRepository.class);
        candidateService = mock(RelationCandidateService.class);
        relationReadService = mock(RelationProjectionReadService.class);
        validationService = mock(RelationValidationService.class);
        workspaceResolver = mock(WorkspaceResolver.class);
        service = new RelationProposalService(
                nodeRepository,
                proposalRepository,
                candidateService,
                relationReadService,
                validationService,
                workspaceResolver);
    }

    @Test
    void relationInRepositoryBDoesNotSuppressRepositoryACandidate() {
        RepositoryContext repositoryA = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        RepositoryContext repositoryB = RepositoryContext.workspace(
                "repo-b", "workspace-b", "feature/b", "bob");
        TaxonomyNode source = source("BP", "BP");
        TaxonomyNodeDto candidate = candidate("CR", "CR");
        prepareCandidate(source, candidate, RelationType.RELATED_TO);
        when(relationReadService.readIdentitySnapshot(repositoryA))
                .thenReturn(snapshot("a", Set.of()));
        when(relationReadService.readIdentitySnapshot(repositoryB))
                .thenReturn(snapshot("b", Set.of(new RelationIdentity(
                        "BP", RelationType.RELATED_TO, "CR"))));
        when(validationService.validate(
                source,
                candidate,
                RelationType.RELATED_TO,
                0,
                1,
                repositoryA)).thenReturn(
                        RelationValidationService.ValidationResult.fail(
                                "stop after duplicate boundary"));

        service.proposeRelationsInContext(
                "BP", RelationType.RELATED_TO, 1, repositoryA);

        verify(validationService).validate(
                source,
                candidate,
                RelationType.RELATED_TO,
                0,
                1,
                repositoryA);
        clearInvocations(validationService);

        service.proposeRelationsInContext(
                "BP", RelationType.RELATED_TO, 1, repositoryB);

        verifyNoInteractions(validationService);
        verify(relationReadService).readIdentitySnapshot(repositoryA);
        verify(relationReadService).readIdentitySnapshot(repositoryB);
    }

    @Test
    void privateWorkspaceRelationDoesNotSuppressSiblingWorkspaceCandidate() {
        RepositoryContext workspaceA1 = RepositoryContext.workspace(
                "repo-a", "workspace-a1", "feature/a1", "alice");
        RepositoryContext workspaceA2 = RepositoryContext.workspace(
                "repo-a", "workspace-a2", "feature/a2", "bob");
        TaxonomyNode source = source("BP", "BP");
        TaxonomyNodeDto candidate = candidate("CR", "CR");
        prepareCandidate(source, candidate, RelationType.RELATED_TO);
        when(relationReadService.readIdentitySnapshot(workspaceA1))
                .thenReturn(snapshot("1", Set.of()));
        when(relationReadService.readIdentitySnapshot(workspaceA2))
                .thenReturn(snapshot("2", Set.of(new RelationIdentity(
                        "BP", RelationType.RELATED_TO, "CR"))));
        when(validationService.validate(
                any(TaxonomyNode.class),
                any(TaxonomyNodeDto.class),
                any(RelationType.class),
                anyInt(),
                anyInt(),
                any(RepositoryContext.class)))
                .thenReturn(RelationValidationService.ValidationResult.fail(
                        "stop after duplicate boundary"));

        service.proposeRelationsInContext(
                "BP", RelationType.RELATED_TO, 1, workspaceA1);
        verify(validationService).validate(
                source,
                candidate,
                RelationType.RELATED_TO,
                0,
                1,
                workspaceA1);
        clearInvocations(validationService);

        service.proposeRelationsInContext(
                "BP", RelationType.RELATED_TO, 1, workspaceA2);
        verifyNoInteractions(validationService);
    }

    @Test
    void proposalRunResolvesOneIdentitySnapshotForAllCandidates() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        TaxonomyNode source = source("BP", "BP");
        TaxonomyNodeDto first = candidate("CR", "CR");
        TaxonomyNodeDto second = candidate("CP", "CP");
        when(nodeRepository.findByCode("BP"))
                .thenReturn(Optional.of(source));
        when(candidateService.findCandidates(
                source, RelationType.RELATED_TO, 2))
                .thenReturn(List.of(first, second));
        when(relationReadService.readIdentitySnapshot(context))
                .thenReturn(snapshot("a", Set.of()));
        when(proposalRepository.existsInRepositoryWorkspace(
                eq("repo-a"),
                eq("BP"),
                any(),
                eq(RelationType.RELATED_TO),
                eq("workspace-a")))
                .thenReturn(false);
        when(validationService.validate(
                any(TaxonomyNode.class),
                any(TaxonomyNodeDto.class),
                any(RelationType.class),
                anyInt(),
                anyInt(),
                any(RepositoryContext.class)))
                .thenReturn(RelationValidationService.ValidationResult.fail(
                        "not relevant"));

        service.proposeRelationsInContext(
                "BP", RelationType.RELATED_TO, 2, context);

        verify(relationReadService, times(1)).readIdentitySnapshot(context);
        verify(validationService, times(2)).validate(
                eq(source),
                any(TaxonomyNodeDto.class),
                eq(RelationType.RELATED_TO),
                anyInt(),
                eq(2),
                eq(context));
    }

    @Test
    void hypothesisProposalIsNotCreatedForAnExistingActiveRelation() {
        RepositoryContext context = RepositoryContext.workspace(
                "repo-a", "workspace-a", "feature/a", "alice");
        TaxonomyNode source = source("BP", "BP");
        TaxonomyNode target = source("CR", "CR");
        when(nodeRepository.findByCode("BP"))
                .thenReturn(Optional.of(source));
        when(nodeRepository.findByCode("CR"))
                .thenReturn(Optional.of(target));
        when(proposalRepository.existsInRepositoryWorkspace(
                "repo-a",
                "BP",
                "CR",
                RelationType.RELATED_TO,
                "workspace-a"))
                .thenReturn(false);
        when(relationReadService.readIdentitySnapshot(context))
                .thenReturn(snapshot("a", Set.of(new RelationIdentity(
                        "BP", RelationType.RELATED_TO, "CR"))));

        var result = service.createFromHypothesisInContext(
                "BP",
                "CR",
                RelationType.RELATED_TO,
                0.8,
                "candidate",
                context);

        assertThat(result).isNull();
        verify(proposalRepository, never()).save(any(RelationProposal.class));
    }

    private void prepareCandidate(
            TaxonomyNode source,
            TaxonomyNodeDto candidate,
            RelationType type) {
        when(nodeRepository.findByCode(source.getCode()))
                .thenReturn(Optional.of(source));
        when(candidateService.findCandidates(source, type, 1))
                .thenReturn(List.of(candidate));
        when(proposalRepository.existsInRepositoryWorkspace(
                any(), any(), any(), eq(type), any()))
                .thenReturn(false);
    }

    private static IdentitySnapshot snapshot(
            String digit,
            Set<RelationIdentity> identities) {
        return new IdentitySnapshot(
                ReadModel.PROJECTION,
                ReadinessState.READY,
                digit.repeat(40),
                identities);
    }

    private static TaxonomyNode source(String code, String root) {
        TaxonomyNode node = new TaxonomyNode();
        node.setCode(code);
        node.setTaxonomyRoot(root);
        node.setNameEn(code);
        return node;
    }

    private static TaxonomyNodeDto candidate(String code, String root) {
        TaxonomyNodeDto candidate = new TaxonomyNodeDto();
        candidate.setCode(code);
        candidate.setTaxonomyRoot(root);
        candidate.setNameEn(code);
        return candidate;
    }
}
