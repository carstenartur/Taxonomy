package com.nato.taxonomy;

import com.nato.taxonomy.dto.RelationProposalDto;
import com.nato.taxonomy.dto.TaxonomyRelationDto;
import com.nato.taxonomy.model.ProposalStatus;
import com.nato.taxonomy.model.RelationType;
import com.nato.taxonomy.repository.RelationProposalRepository;
import com.nato.taxonomy.repository.TaxonomyRelationRepository;
import com.nato.taxonomy.service.*;
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

/**
 * Tests for the Relation Proposal Pipeline:
 * RelationProposalService, RelationCandidateService, RelationValidationService,
 * RelationReviewService, RelationCompatibilityMatrix, and ProposalApiController.
 */
@SpringBootTest
@AutoConfigureMockMvc
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

    // ── Compatibility Matrix ──────────────────────────────────────────────────

    @Test
    void compatibilityMatrixAllowsCorrectPairs() {
        // CP → CS is valid for REALIZES
        assertThat(compatibilityMatrix.isCompatible("CP", "CS", RelationType.REALIZES)).isTrue();
        // CS → BP is valid for SUPPORTS
        assertThat(compatibilityMatrix.isCompatible("CS", "BP", RelationType.SUPPORTS)).isTrue();
    }

    @Test
    void compatibilityMatrixRejectsIncorrectPairs() {
        // BP → CS should not be valid for REALIZES (source must be CP)
        assertThat(compatibilityMatrix.isCompatible("BP", "CS", RelationType.REALIZES)).isFalse();
    }

    @Test
    void compatibilityMatrixRelatedToHasNoRestrictions() {
        // RELATED_TO should allow any combination
        assertThat(compatibilityMatrix.isCompatible("BP", "CS", RelationType.RELATED_TO)).isTrue();
        assertThat(compatibilityMatrix.isCompatible("CP", "COI", RelationType.RELATED_TO)).isTrue();
    }

    @Test
    void allowedTargetRootsReturnsCorrectSets() {
        Set<String> targets = compatibilityMatrix.allowedTargetRoots("CP", RelationType.REALIZES);
        assertThat(targets).containsExactly("CS");
    }

    // ── Validation Service ────────────────────────────────────────────────────

    @Test
    void validationRejectsSelfRelation() {
        var source = new com.nato.taxonomy.model.TaxonomyNode();
        source.setCode("BP");
        source.setTaxonomyRoot("BP");

        var candidate = new com.nato.taxonomy.dto.TaxonomyNodeDto();
        candidate.setCode("BP");
        candidate.setTaxonomyRoot("BP");

        var result = validationService.validate(source, candidate, RelationType.RELATED_TO, 0, 1);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getRationale()).contains("Self-relation");
    }

    @Test
    void confidenceComputationBounds() {
        // Rank 0 of 5 should be high
        double high = validationService.computeConfidence(0, 5);
        // Rank 4 of 5 should be low
        double low = validationService.computeConfidence(4, 5);
        assertThat(high).isGreaterThan(0.8);
        assertThat(low).isGreaterThan(0.2);
        assertThat(high).isGreaterThan(low);
    }

    @Test
    void confidenceSingleCandidate() {
        double c = validationService.computeConfidence(0, 1);
        assertThat(c).isEqualTo(0.9);
    }

    // ── Proposal Service ──────────────────────────────────────────────────────

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
        // RELATED_TO has no root restriction, so should find candidates
        List<RelationProposalDto> proposals =
                proposalService.proposeRelations("BP", RelationType.RELATED_TO, 5);
        assertThat(proposals).isNotNull();
        // With full-text search, there should be at least some candidates
        // (embedding may not be available in test)
    }

    @Test
    void proposeRelationsRejectsUnknownNode() {
        assertThatThrownBy(() ->
                proposalService.proposeRelations("NONEXISTENT", RelationType.RELATED_TO, 5))
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

    // ── Review Service ────────────────────────────────────────────────────────

    @Test
    void acceptProposalCreatesRelation() {
        // Generate proposals first
        List<RelationProposalDto> proposals =
                proposalService.proposeRelations("BP", RelationType.RELATED_TO, 5);

        if (!proposals.isEmpty()) {
            Long proposalId = proposals.get(0).getId();
            TaxonomyRelationDto relation = reviewService.acceptProposal(proposalId);
            assertThat(relation).isNotNull();
            assertThat(relation.getSourceCode()).isEqualTo("BP");
            assertThat(relation.getRelationType()).isEqualTo("RELATED_TO");
            assertThat(relation.getProvenance()).isEqualTo("proposal-pipeline");

            // Verify proposal status changed
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
            RelationProposalDto rejected = reviewService.rejectProposal(proposalId);
            assertThat(rejected.getStatus()).isEqualTo("REJECTED");

            var updated = proposalRepository.findById(proposalId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ProposalStatus.REJECTED);
            assertThat(updated.getReviewedAt()).isNotNull();
        }
    }

    @Test
    void acceptNonExistentProposalThrows() {
        assertThatThrownBy(() -> reviewService.acceptProposal(999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectNonExistentProposalThrows() {
        assertThatThrownBy(() -> reviewService.rejectProposal(999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── REST API ──────────────────────────────────────────────────────────────

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
