package com.taxonomy;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.repository.TaxonomyNodeRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.relations.service.RelationReviewService;
import com.taxonomy.workspace.model.SystemRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regression tests for exact repository/workspace proposal and relation access. */
@SpringBootTest
@Transactional
@WithMockUser(username = "qa-admin", roles = {"USER", "ARCHITECT", "ADMIN"})
class WorkspaceRelationIsolationTests {

    @Autowired private TaxonomyNodeRepository nodeRepository;
    @Autowired private RelationProposalRepository proposalRepository;
    @Autowired private TaxonomyRelationRepository relationRepository;
    @Autowired private RelationProposalService proposalService;
    @Autowired private RelationReviewService reviewService;
    @Autowired private TaxonomyRelationService relationService;
    @Autowired private SystemRepositoryService systemRepositoryService;

    @Test
    void centralContextCannotReviewForeignWorkspaceProposal() {
        RelationProposal foreign = proposalRepository.saveAndFlush(
                newProposal("qa-foreign-workspace", "other-user"));

        assertThatThrownBy(() -> reviewService.acceptProposal(
                foreign.getId(), centralWriteContext()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active repository/workspace");

        RelationProposal unchanged = proposalRepository.findById(foreign.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ProposalStatus.PENDING);
    }

    @Test
    void identicalProposalTriplesCanExistInSeparateWorkspaces() {
        RelationProposal first = proposalRepository.save(newProposal("qa-workspace-a", "alice"));
        RelationProposal second = proposalRepository.save(newProposal("qa-workspace-b", "bob"));
        proposalRepository.flush();
        String repositoryId = primaryRepository().getRepositoryId();

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(proposalRepository.existsInRepositoryWorkspace(
                repositoryId,
                "BP",
                "BP",
                RelationType.RELATED_TO,
                "qa-workspace-a")).isTrue();
        assertThat(proposalRepository.existsInRepositoryWorkspace(
                repositoryId,
                "BP",
                "BP",
                RelationType.RELATED_TO,
                "qa-workspace-b")).isTrue();
        assertThat(proposalRepository.existsInRepositoryWorkspace(
                repositoryId,
                "BP",
                "BP",
                RelationType.RELATED_TO,
                null)).isFalse();
    }

    @Test
    void centralProposalReadsDoNotExposeForeignWorkspaceRows() {
        RelationProposal foreign = proposalRepository.saveAndFlush(
                newProposal("qa-private-workspace", "private-user"));

        assertThat(proposalService.getAllProposalsInContext(centralReadContext()))
                .noneMatch(dto -> dto.getId().equals(foreign.getId()));
    }

    @Test
    void exactWorkspaceDeletePreservesEquivalentRelationInOtherWorkspace() {
        RepositoryContext workspaceA = workspaceContext("qa-workspace-a", "alice");
        RepositoryContext workspaceB = workspaceContext("qa-workspace-b", "bob");
        relationService.createRelationInContext(
                "BP",
                "BP",
                RelationType.RELATED_TO,
                "workspace A",
                "qa-test",
                workspaceA);
        relationService.createRelationInContext(
                "BP",
                "BP",
                RelationType.RELATED_TO,
                "workspace B",
                "qa-test",
                workspaceB);

        relationService.deleteRelationBySourceTargetTypeInContext(
                "BP", "BP", RelationType.RELATED_TO, workspaceA);

        assertThat(relationRepository
                .findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                        workspaceA.repositoryId(),
                        "qa-workspace-a",
                        "BP",
                        "BP",
                        RelationType.RELATED_TO))
                .isEmpty();
        assertThat(relationRepository
                .findByRepositoryIdAndWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                        workspaceB.repositoryId(),
                        "qa-workspace-b",
                        "BP",
                        "BP",
                        RelationType.RELATED_TO))
                .hasSize(1);
    }

    @Test
    void centralRelationReadsDoNotExposeForeignWorkspaceRows() {
        TaxonomyNode node = nodeRepository.findByCode("BP").orElseThrow();
        TaxonomyRelation foreign = new TaxonomyRelation();
        foreign.setRepositoryId(primaryRepository().getRepositoryId());
        foreign.setSourceNode(node);
        foreign.setTargetNode(node);
        foreign.setRelationType(RelationType.RELATED_TO);
        foreign.setDescription("private relation");
        foreign.setProvenance("qa-test");
        foreign.setWorkspaceId("qa-private-workspace");
        foreign.setOwnerUsername("private-user");
        foreign = relationRepository.saveAndFlush(foreign);

        Long foreignId = foreign.getId();
        RepositoryContext central = centralReadContext();
        assertThat(relationService.getAllRelationsInContext(central))
                .noneMatch(dto -> dto.getId().equals(foreignId));
        assertThat(relationService.countRelationsInContext(central))
                .isEqualTo(relationRepository.countCentralByRepository(
                        central.repositoryId()));
    }

    private RelationProposal newProposal(String workspaceId, String owner) {
        TaxonomyNode node = nodeRepository.findByCode("BP").orElseThrow();
        RelationProposal proposal = new RelationProposal();
        proposal.setRepositoryId(primaryRepository().getRepositoryId());
        proposal.setSourceNode(node);
        proposal.setTargetNode(node);
        proposal.setRelationType(RelationType.RELATED_TO);
        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setConfidence(0.75);
        proposal.setRationale("workspace isolation regression test");
        proposal.setProvenance("qa-test");
        proposal.setWorkspaceId(workspaceId);
        proposal.setOwnerUsername(owner);
        return proposal;
    }

    private RepositoryContext centralReadContext() {
        SystemRepository primary = primaryRepository();
        return RepositoryContext.centralRead(
                primary.getRepositoryId(), primary.getDefaultBranch(), "qa-admin");
    }

    private RepositoryContext centralWriteContext() {
        SystemRepository primary = primaryRepository();
        return new RepositoryContext(
                primary.getRepositoryId(),
                null,
                primary.getDefaultBranch(),
                "qa-admin",
                RepositoryScope.CENTRAL_WRITE);
    }

    private RepositoryContext workspaceContext(String workspaceId, String username) {
        SystemRepository primary = primaryRepository();
        return RepositoryContext.workspace(
                primary.getRepositoryId(),
                workspaceId,
                primary.getDefaultBranch(),
                username);
    }

    private SystemRepository primaryRepository() {
        return systemRepositoryService.getPrimaryRepository();
    }
}
