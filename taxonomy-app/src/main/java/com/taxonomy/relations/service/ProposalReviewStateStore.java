package com.taxonomy.relations.service;

import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Reads and advances proposal bookkeeping in short transactions around an
 * independently authoritative Git command.
 */
@Service
public class ProposalReviewStateStore {

    private final RelationProposalRepository proposalRepository;

    public ProposalReviewStateStore(
            RelationProposalRepository proposalRepository) {
        this.proposalRepository = Objects.requireNonNull(
                proposalRepository, "proposalRepository");
    }

    @Transactional(readOnly = true)
    public ProposalSnapshot require(
            Long proposalId,
            RepositoryContext context) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationProposal proposal = find(proposalId, tenant, false);
        return new ProposalSnapshot(
                proposal.getId(),
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                proposal.getStatus(),
                proposal.getConfidence(),
                proposal.getRationale(),
                proposal.getProvenance());
    }

    @Transactional
    public ProposalStatus transition(
            Long proposalId,
            RepositoryContext context,
            ProposalStatus expected,
            ProposalStatus target) {
        RepositoryContext tenant = requireWritableContext(context);
        RelationProposal proposal = find(proposalId, tenant, true);
        if (proposal.getStatus() != expected) {
            throw new ProposalStateConflictException(
                    "Proposal " + proposalId + " is " + proposal.getStatus()
                            + ", expected " + expected);
        }
        proposal.setStatus(target);
        proposal.setReviewedAt(target == ProposalStatus.PENDING
                ? null : Instant.now());
        proposalRepository.save(proposal);
        return target;
    }

    private RelationProposal find(
            Long proposalId,
            RepositoryContext context,
            boolean forUpdate) {
        if (proposalId == null) {
            throw new IllegalArgumentException("proposalId must not be null");
        }
        return (forUpdate
                ? proposalRepository.findByIdInRepositoryWorkspaceForUpdate(
                        context.repositoryId(),
                        proposalId,
                        context.workspaceId())
                : proposalRepository.findByIdInRepositoryWorkspace(
                        context.repositoryId(),
                        proposalId,
                        context.workspaceId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Proposal not found in active repository/workspace: "
                                + proposalId));
    }

    static RepositoryContext requireWritableContext(
            RepositoryContext context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "RepositoryContext must not be null");
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

    public record ProposalSnapshot(
            Long id,
            String sourceCode,
            String targetCode,
            RelationType relationType,
            ProposalStatus status,
            double confidence,
            String rationale,
            String provenance) {
        public ProposalSnapshot {
            id = Objects.requireNonNull(id, "id");
            sourceCode = Objects.requireNonNull(sourceCode, "sourceCode");
            targetCode = Objects.requireNonNull(targetCode, "targetCode");
            relationType = Objects.requireNonNull(relationType, "relationType");
            status = Objects.requireNonNull(status, "status");
        }
    }

    public static final class ProposalStateConflictException
            extends IllegalStateException {
        public ProposalStateConflictException(String message) {
            super(message);
        }
    }
}
