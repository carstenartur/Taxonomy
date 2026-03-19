package com.taxonomy.relations.service;

import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.*;
import com.taxonomy.relations.repository.RelationProposalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.model.RelationProposal;

/**
 * Handles the human review workflow for relation proposals.
 * Accepted proposals are converted into actual {@link TaxonomyRelation} entities.
 */
@Service
public class RelationReviewService {

    private static final Logger log = LoggerFactory.getLogger(RelationReviewService.class);

    private final RelationProposalRepository proposalRepository;
    private final TaxonomyRelationService relationService;
    private final RelationProposalService proposalService;

    public RelationReviewService(RelationProposalRepository proposalRepository,
                                 TaxonomyRelationService relationService,
                                 RelationProposalService proposalService) {
        this.proposalRepository = proposalRepository;
        this.relationService = relationService;
        this.proposalService = proposalService;
    }

    /**
     * Accept a proposal: creates a real TaxonomyRelation and marks the proposal as ACCEPTED.
     *
     * <p>The relation is created in the proposal's workspace (using the proposal's stored
     * {@code workspaceId}/{@code ownerUsername}). For legacy proposals with null workspace,
     * the relation is created in the shared/legacy scope.
     */
    @Transactional
    public TaxonomyRelationDto acceptProposal(Long proposalId) {
        RelationProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found: " + proposalId));

        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already " + proposal.getStatus());
        }

        // Create the real relation in the proposal's workspace (not the current user's)
        String workspaceId = proposal.getWorkspaceId();
        String ownerUsername = proposal.getOwnerUsername();
        TaxonomyRelationDto relation = relationService.createRelation(
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                proposal.getRationale(),
                "proposal-pipeline",
                workspaceId, ownerUsername);

        // Mark proposal as accepted
        proposal.setStatus(ProposalStatus.ACCEPTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);

        log.info("Accepted proposal {}: {} → {} [{}]",
                proposalId,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType());

        return relation;
    }

    /**
     * Reject a proposal.
     */
    @Transactional
    public RelationProposalDto rejectProposal(Long proposalId) {
        RelationProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found: " + proposalId));

        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already " + proposal.getStatus());
        }

        proposal.setStatus(ProposalStatus.REJECTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);

        log.info("Rejected proposal {}: {} → {} [{}]",
                proposalId,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType());

        return proposalService.toDto(proposal);
    }

    /**
     * Revert a previously accepted or rejected proposal back to PENDING status.
     * If the proposal was accepted, the corresponding relation is deleted.
     */
    @Transactional
    public RelationProposalDto revertProposal(Long proposalId) {
        RelationProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found: " + proposalId));

        if (proposal.getStatus() == ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already PENDING");
        }

        ProposalStatus oldStatus = proposal.getStatus();

        // If accepted, remove the created relation
        if (oldStatus == ProposalStatus.ACCEPTED) {
            relationService.deleteRelationBySourceTargetType(
                    proposal.getSourceNode().getCode(),
                    proposal.getTargetNode().getCode(),
                    proposal.getRelationType());
        }

        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setReviewedAt(null);
        proposalRepository.save(proposal);

        log.info("Reverted proposal {} from {} to PENDING: {} → {} [{}]",
                proposalId, oldStatus,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType());

        return proposalService.toDto(proposal);
    }
}
