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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for exact-scope proposal and relation access.
 *
 * <p>The mock user has no provisioned workspace and therefore resolves to the
 * shared context. Shared context must never mean unrestricted access to all
 * personal workspaces.
 */
@SpringBootTest
@Transactional
@WithMockUser(username = "qa-admin", roles = {"USER", "ARCHITECT", "ADMIN"})
class WorkspaceRelationIsolationTests {

    @Autowired
    private TaxonomyNodeRepository nodeRepository;

    @Autowired
    private RelationProposalRepository proposalRepository;

    @Autowired
    private TaxonomyRelationRepository relationRepository;

    @Autowired
    private RelationProposalService proposalService;

    @Autowired
    private RelationReviewService reviewService;

    @Autowired
    private TaxonomyRelationService relationService;

    @Test
    void sharedContextCannotReviewForeignWorkspaceProposal() {
        RelationProposal foreign = proposalRepository.saveAndFlush(
                newProposal("qa-foreign-workspace", "other-user"));

        assertThatThrownBy(() -> reviewService.acceptProposal(foreign.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active workspace");

        RelationProposal unchanged = proposalRepository.findById(foreign.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ProposalStatus.PENDING);
    }

    @Test
    void identicalProposalTriplesCanExistInSeparateWorkspaces() {
        RelationProposal first = proposalRepository.save(newProposal("qa-workspace-a", "alice"));
        RelationProposal second = proposalRepository.save(newProposal("qa-workspace-b", "bob"));
        proposalRepository.flush();

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(proposalRepository.existsInWorkspace(
                "BP", "BP", RelationType.RELATED_TO, "qa-workspace-a")).isTrue();
        assertThat(proposalRepository.existsInWorkspace(
                "BP", "BP", RelationType.RELATED_TO, "qa-workspace-b")).isTrue();
        assertThat(proposalRepository.existsInWorkspace(
                "BP", "BP", RelationType.RELATED_TO, null)).isFalse();
    }

    @Test
    void sharedProposalReadsDoNotExposeForeignWorkspaceRows() {
        RelationProposal foreign = proposalRepository.saveAndFlush(
                newProposal("qa-private-workspace", "private-user"));

        assertThat(proposalService.getAllProposals())
                .noneMatch(dto -> dto.getId().equals(foreign.getId()));
    }

    @Test
    void exactWorkspaceDeletePreservesEquivalentRelationInOtherWorkspace() {
        relationService.createRelation(
                "BP", "BP", RelationType.RELATED_TO, "workspace A", "qa-test",
                "qa-workspace-a", "alice");
        relationService.createRelation(
                "BP", "BP", RelationType.RELATED_TO, "workspace B", "qa-test",
                "qa-workspace-b", "bob");

        relationService.deleteRelationBySourceTargetType(
                "BP", "BP", RelationType.RELATED_TO, "qa-workspace-a");

        assertThat(relationRepository
                .findByWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                        "qa-workspace-a", "BP", "BP", RelationType.RELATED_TO))
                .isEmpty();
        assertThat(relationRepository
                .findByWorkspaceIdAndSourceNodeCodeAndTargetNodeCodeAndRelationType(
                        "qa-workspace-b", "BP", "BP", RelationType.RELATED_TO))
                .hasSize(1);
    }

    @Test
    void sharedRelationReadsDoNotExposeForeignWorkspaceRows() {
        TaxonomyNode node = nodeRepository.findByCode("BP").orElseThrow();
        TaxonomyRelation foreign = new TaxonomyRelation();
        foreign.setSourceNode(node);
        foreign.setTargetNode(node);
        foreign.setRelationType(RelationType.RELATED_TO);
        foreign.setDescription("private relation");
        foreign.setProvenance("qa-test");
        foreign.setWorkspaceId("qa-private-workspace");
        foreign.setOwnerUsername("private-user");
        foreign = relationRepository.saveAndFlush(foreign);

        Long foreignId = foreign.getId();
        assertThat(relationService.getAllRelations(null))
                .noneMatch(dto -> dto.getId().equals(foreignId));
        assertThat(relationService.countRelations(null))
                .isEqualTo(relationRepository.countByWorkspaceIdIsNull());
    }

    private RelationProposal newProposal(String workspaceId, String owner) {
        TaxonomyNode node = nodeRepository.findByCode("BP").orElseThrow();
        RelationProposal proposal = new RelationProposal();
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
}
