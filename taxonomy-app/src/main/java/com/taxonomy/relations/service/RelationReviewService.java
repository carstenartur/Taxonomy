package com.taxonomy.relations.service;

import com.taxonomy.catalog.service.TaxonomyRelationService;
import com.taxonomy.dto.RelationProposalDto;
import com.taxonomy.dto.TaxonomyRelationDto;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Handles human review of relation proposals in an explicit repository tenant. */
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

    /** Accept a proposal and create its relation in the exact writable context. */
    @Transactional
    public TaxonomyRelationDto acceptProposal(
            Long proposalId, RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationProposal proposal = findProposalInContext(proposalId, tenant);
        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already " + proposal.getStatus());
        }

        TaxonomyRelationDto relation = relationService.createRelationInContext(
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                proposal.getRationale(),
                "proposal-pipeline",
                tenant);

        proposal.setStatus(ProposalStatus.ACCEPTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);
        log.info("Accepted proposal {}: {} → {} [{}] "
                        + "(repository={}, workspace={})",
                proposalId,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                tenant.repositoryId(),
                tenant.workspaceId());
        return relation;
    }

    /** Reject a proposal in the exact writable context. */
    @Transactional
    public RelationProposalDto rejectProposal(
            Long proposalId, RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationProposal proposal = findProposalInContext(proposalId, tenant);
        if (proposal.getStatus() != ProposalStatus.PENDING) {
            throw new IllegalStateException(
                    "Proposal " + proposalId + " is already " + proposal.getStatus());
        }

        proposal.setStatus(ProposalStatus.REJECTED);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);
        log.info("Rejected proposal {}: {} → {} [{}] "
                        + "(repository={}, workspace={})",
                proposalId,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                tenant.repositoryId(),
                tenant.workspaceId());
        return proposalService.toDto(proposal);
    }

    /**
     * Revert a reviewed proposal in the exact writable context. If accepted,
     * only the relation in that same repository/workspace is removed.
     */
    @Transactional
    public RelationProposalDto revertProposal(
            Long proposalId, RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationProposal proposal = findProposalInContext(proposalId, tenant);
        if (proposal.getStatus() == ProposalStatus.PENDING) {
            throw new IllegalStateException("Proposal " + proposalId + " is already PENDING");
        }

        ProposalStatus oldStatus = proposal.getStatus();
        if (oldStatus == ProposalStatus.ACCEPTED) {
            relationService.deleteRelationBySourceTargetTypeInContext(
                    proposal.getSourceNode().getCode(),
                    proposal.getTargetNode().getCode(),
                    proposal.getRelationType(),
                    tenant);
        }

        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setReviewedAt(null);
        proposalRepository.save(proposal);
        log.info("Reverted proposal {} from {} to PENDING: {} → {} [{}] "
                        + "(repository={}, workspace={})",
                proposalId,
                oldStatus,
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                tenant.repositoryId(),
                tenant.workspaceId());
        return proposalService.toDto(proposal);
    }

    private RelationProposal findProposalInContext(
            Long proposalId, RepositoryContext context) {
        return proposalRepository.findByIdInRepositoryWorkspace(
                        context.repositoryId(),
                        proposalId,
                        context.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found in active repository/workspace: " + proposalId));
    }

    private static RepositoryContext requireWritableContext(
            RepositoryContext context) {
        if (context == null) {
            throw new IllegalArgumentException("RepositoryContext must not be null");
        }
        if (context.workspaceId() == null
                && context.scope() != RepositoryScope.CENTRAL_WRITE) {
            throw new IllegalArgumentException(
                    "Central proposal review requires CENTRAL_WRITE scope");
        }
        if (context.workspaceId() != null
                && context.scope() != RepositoryScope.WORKSPACE
                && context.scope() != RepositoryScope.FORK) {
            throw new IllegalArgumentException(
                    "Workspace proposal review requires WORKSPACE or FORK scope");
        }
        return context;
    }
}
