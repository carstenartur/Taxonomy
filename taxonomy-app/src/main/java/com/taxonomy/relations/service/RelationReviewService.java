package com.taxonomy.relations.service;

import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/** Handles human review of relation proposals in an explicit workspace. */
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

    /** Accepts a proposal and creates the relation in the exact supplied workspace. */
    @Transactional
    public TaxonomyRelationDto acceptProposal(Long proposalId, WorkspaceContext context) {
        WorkspaceContext requiredContext = requireContext(context);
        RelationProposal proposal = findProposalInWorkspace(proposalId, requiredContext);
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
                requiredContext.workspaceId(), requiredContext.username());

        proposal.setStatus(ProposalStatus.ACCEPTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);
        log.info("Accepted proposal {}: {} → {} [{}] (workspace={})",
                proposalId, proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(), proposal.getRelationType(),
                requiredContext.workspaceId());
        return relation;
    }

    /** Rejects a proposal in the exact supplied workspace. */
    @Transactional
    public RelationProposalDto rejectProposal(Long proposalId, WorkspaceContext context) {
        WorkspaceContext requiredContext = requireContext(context);
        RelationProposal proposal = findProposalInWorkspace(proposalId, requiredContext);
        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already " + proposal.getStatus());
        }

        proposal.setStatus(ProposalStatus.REJECTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);
        log.info("Rejected proposal {}: {} → {} [{}] (workspace={})",
                proposalId, proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(), proposal.getRelationType(),
                requiredContext.workspaceId());
        return proposalService.toDto(proposal);
    }

    /**
     * Reverts a reviewed proposal in the exact supplied workspace. If accepted,
     * only the relation in that same workspace is removed.
     */
    @Transactional
    public RelationProposalDto revertProposal(Long proposalId, WorkspaceContext context) {
        WorkspaceContext requiredContext = requireContext(context);
        RelationProposal proposal = findProposalInWorkspace(proposalId, requiredContext);
        if (proposal.getStatus() == ProposalStatus.PENDING) {
            throw new IllegalStateException("Proposal " + proposalId + " is already PENDING");
        }

        ProposalStatus oldStatus = proposal.getStatus();
        if (oldStatus == ProposalStatus.ACCEPTED) {
            relationService.deleteRelationBySourceTargetType(
                    proposal.getSourceNode().getCode(),
                    proposal.getTargetNode().getCode(),
                    proposal.getRelationType(),
                    requiredContext.workspaceId());
        }

        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setReviewedAt(null);
        proposalRepository.save(proposal);
        log.info("Reverted proposal {} from {} to PENDING: {} → {} [{}] (workspace={})",
                proposalId, oldStatus, proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(), proposal.getRelationType(),
                requiredContext.workspaceId());
        return proposalService.toDto(proposal);
    }

    private RelationProposal findProposalInWorkspace(Long proposalId,
                                                       WorkspaceContext context) {
        return proposalRepository.findByIdInWorkspace(proposalId, context.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found in active workspace: " + proposalId));
    }

    private static WorkspaceContext requireContext(WorkspaceContext context) {
        return Objects.requireNonNull(context, "Workspace context is required");
    }
}