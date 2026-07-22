package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
    private final WorkspaceContextResolver contextResolver;

    public RelationReviewService(RelationProposalRepository proposalRepository,
                                 TaxonomyRelationService relationService,
                                 RelationProposalService proposalService,
                                 WorkspaceContextResolver contextResolver) {
        this.proposalRepository = proposalRepository;
        this.relationService = relationService;
        this.proposalService = proposalService;
        this.contextResolver = contextResolver;
    }

    /**
     * Accept a proposal in the active workspace: creates a real TaxonomyRelation
     * and marks the proposal as ACCEPTED.
     */
    @Transactional
    public TaxonomyRelationDto acceptProposal(Long proposalId) {
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        RelationProposal proposal = findProposalInActiveWorkspace(proposalId, context);

        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already " + proposal.getStatus());
        }

        TaxonomyRelationDto relation = relationService.createRelation(
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                proposal.getRationale(),
                "proposal-pipeline",
                context.workspaceId(), context.username());

        proposal.setStatus(ProposalStatus.ACCEPTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);

        log.info("Accepted proposal {}: {} → {} [{}] (workspace={})",
                proposalId,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                context.workspaceId());

        return relation;
    }

    /**
     * Reject a proposal in the active workspace.
     */
    @Transactional
    public RelationProposalDto rejectProposal(Long proposalId) {
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        RelationProposal proposal = findProposalInActiveWorkspace(proposalId, context);

        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already " + proposal.getStatus());
        }

        proposal.setStatus(ProposalStatus.REJECTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);

        log.info("Rejected proposal {}: {} → {} [{}] (workspace={})",
                proposalId,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                context.workspaceId());

        return proposalService.toDto(proposal);
    }

    /**
     * Revert a previously accepted or rejected proposal in the active workspace
     * back to PENDING status. If the proposal was accepted, only the relation in
     * that exact workspace is deleted.
     */
    @Transactional
    public RelationProposalDto revertProposal(Long proposalId) {
        WorkspaceContext context = contextResolver.resolveCurrentContext();
        RelationProposal proposal = findProposalInActiveWorkspace(proposalId, context);

        if (proposal.getStatus() == ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already PENDING");
        }

        ProposalStatus oldStatus = proposal.getStatus();

        if (oldStatus == ProposalStatus.ACCEPTED) {
            relationService.deleteRelationBySourceTargetType(
                    proposal.getSourceNode().getCode(),
                    proposal.getTargetNode().getCode(),
                    proposal.getRelationType(),
                    context.workspaceId());
        }

        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setReviewedAt(null);
        proposalRepository.save(proposal);

        log.info("Reverted proposal {} from {} to PENDING: {} → {} [{}] (workspace={})",
                proposalId, oldStatus,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                context.workspaceId());

        return proposalService.toDto(proposal);
    }

    private RelationProposal findProposalInActiveWorkspace(Long proposalId,
                                                            WorkspaceContext context) {
        return proposalRepository.findByIdInWorkspace(proposalId, context.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found in active workspace: " + proposalId));
    }
}
