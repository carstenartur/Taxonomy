package com.taxonomy;

import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.catalog.repository.TaxonomyRelationRepository;
import com.taxonomy.relations.service.RelationProposalService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.security.test.context.support.WithMockUser;
import com.taxonomy.relations.service.RelationCandidateService;
import com.taxonomy.relations.service.RelationCompatibilityMatrix;
import com.taxonomy.relations.service.RelationReviewService;
import com.taxonomy.relations.service.RelationValidationService;

/** Tests for the relation proposal pipeline and REST API. */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class RelationProposalTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private RelationProposalService proposalService;
    @Autowired private RelationReviewService reviewService;
    @Autowired private RelationCandidateService candidateService;
    @Autowired private RelationValidationService validationService;
    @Autowired private RelationCompatibilityMatrix compatibilityMatrix;
    @Autowired private RelationProposalRepository proposalRepository;
    @Autowired private TaxonomyRelationRepository relationRepository;

    @BeforeEach
    void clean() {
        proposalRepository.deleteAll();
        relationRepository.deleteAll();
    }

    @Test
    void compatibilityMatrixAllowsCorrectPairs() {
        assertThat(compatibilityMatrix.isCompatible("CP", "CR", RelationType.REALIZES)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("CR", "BP", RelationType.SUPPORTS)).isTrue();
    }

    @Test
    void compatibilityMatrixRejectsIncorrectPairs() {
        assertThat(compatibilityMatrix.isCompatible("BP", "CR", RelationType.REALIZES)).isFalse();
    }

    @Test
    void compatibilityMatrixRelatedToHasNoRestrictions() {
        assertThat(compatibilityMatrix.isCompatible("BP", "CR", RelationType.RELATED_TO)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("CP", "CI", RelationType.RELATED_TO)).isTrue();
    }

    @Test
    void allowedTargetRootsReturnsCorrectSets() {
        Set<String> targets = compatibilityMatrix.allowedTargetRoots("CP", RelationType.REALIZES);
        assertThat(targets).containsExactly("CR");
    }

    @Test
    void validationRejectsSelfRelation() {
        var source = new com.taxonomy.catalog.model.TaxonomyNode();
        source.setCode("BP");
        source.setTaxonomyRoot("BP");
        var candidate = new com.taxonomy.dto.TaxonomyNodeDto();
        candidate.setCode("BP");
        candidate.setTaxonomyRoot("BP");

        var result = validationService.validate(source, candidate, RelationType.RELATED_TO, 0, 1);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getRationale()).contains("Self-relation");
    }

    @Test
    void confidenceComputationBounds() {
        double high = validationService.computeConfidence(0, 5);
        double low = validationService.computeConfidence(4, 5);
        assertThat(high).isGreaterThan(0.8);
        assertThat(low).isGreaterThan(0.2);
        assertThat(high).isGreaterThan(low);
    }

    @Test
    void confidenceSingleCandidate() {
        assertThat(validationService.computeConfidence(0, 1)).isEqualTo(0.9);
    }

    @Test
    void contextLoadsWithProposalServices() {
        assertThat(proposalService).isNotNull();
        assertThat(reviewService).isNotNull();
        assertThat(candidateService).isNotNull();
        assertThat(validationService).isNotNull();
        assertThat(compatibilityMatrix).isNotNull();
    }

    @Test
    void proposeRelationsReturnsProposals() {
        List<RelationProposalDto> proposals =
                proposalService.proposeRelations("BP", RelationType.RELATED_TO, 5);
        assertThat(proposals).isNotNull();
    }

    @Test
    void proposeRelationsRejectsUnknownNode() {
        assertThatThrownBy(() -> proposalService.proposeRelations(
                "NONEXISTENT", RelationType.RELATED_TO, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPendingProposalsReturnsEmptyInitially() {
        assertThat(proposalService.getPendingProposals()).isEmpty();
    }

    @Test
    void getAllProposalsReturnsEmptyInitially() {
        assertThat(proposalService.getAllProposals()).isEmpty();
    }

    @Test
    void acceptProposalCreatesRelation() {
        List<RelationProposalDto> proposals =
                proposalService.proposeRelations("BP", RelationType.RELATED_TO, 5);
        if (!proposals.isEmpty()) {
            Long proposalId = proposals.get(0).getId();
            TaxonomyRelationDto relation = reviewService.acceptProposal(
                    proposalId, WorkspaceContext.SHARED);
            assertThat(relation).isNotNull();
            assertThat(relation.getSourceCode()).isEqualTo("BP");
            assertThat(relation.getRelationType()).isEqualTo("RELATED_TO");
            assertThat(relation.getProvenance()).isEqualTo("proposal-pipeline");
            var updated = proposalRepository.findById(proposalId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProposalStatus.ACCEPTED);
            assertThat(updated.getReviewedAt()).isNotNull();
        }
    }

    @Test
    void rejectProposalChangesStatus() {
        List<RelationProposalDto> proposals =
                proposalService.proposeRelations("BP", RelationType.RELATED_TO, 5);
        if (!proposals.isEmpty()) {
            Long proposalId = proposals.get(0).getId();
            RelationProposalDto rejected = reviewService.rejectProposal(
                    proposalId, WorkspaceContext.SHARED);
            assertThat(rejected.getStatus()).isEqualTo("REJECTED");
            var updated = proposalRepository.findById(proposalId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProposalStatus.REJECTED);
            assertThat(updated.getReviewedAt()).isNotNull();
        }
    }

    @Test
    void acceptNonExistentProposalThrows() {
        assertThatThrownBy(() -> reviewService.acceptProposal(
                999L, WorkspaceContext.SHARED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectNonExistentProposalThrows() {
        assertThatThrownBy(() -> reviewService.rejectProposal(
                999L, WorkspaceContext.SHARED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proposalsApiGetAllReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/proposals").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void proposalsApiGetPendingReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/proposals/pending").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void proposalsApiProposeReturnsBadRequestForMissingFields() throws Exception {
        mockMvc.perform(post("/api/proposals/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposalsApiProposeReturnsBadRequestForUnknownType() throws Exception {
        mockMvc.perform(post("/api/proposals/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"relationType\":\"UNKNOWN_TYPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposalsApiProposeReturnsBadRequestForUnknownNode() throws Exception {
        mockMvc.perform(post("/api/proposals/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"NONEXISTENT\",\"relationType\":\"RELATED_TO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposalsApiProposeReturnsOkForValidInput() throws Exception {
        mockMvc.perform(post("/api/proposals/propose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"BP\",\"relationType\":\"RELATED_TO\",\"limit\":\"3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void proposalsApiGetForNodeReturnsOk() throws Exception {
        mockMvc.perform(get("/api/node/BP/proposals").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void proposalsApiAcceptReturnsBadRequestForNonExistent() throws Exception {
        mockMvc.perform(post("/api/proposals/999/accept"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void proposalsApiRejectReturnsBadRequestForNonExistent() throws Exception {
        mockMvc.perform(post("/api/proposals/999/reject"))
                .andExpect(status().isBadRequest());
    }
}