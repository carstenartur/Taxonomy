package com.taxonomy.relations.service;

import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalRepository;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Maps one exact-scope proposal to an immutable command candidate before Git I/O. */
@Service
public class RelationProposalReviewCandidateReader {

    private final RelationProposalRepository proposalRepository;

    public RelationProposalReviewCandidateReader(
            RelationProposalRepository proposalRepository) {
        this.proposalRepository = Objects.requireNonNull(
                proposalRepository, "proposalRepository");
    }

    @Transactional(readOnly = true)
    public Candidate read(RepositoryContext context, Long proposalId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(proposalId, "proposalId");
        RelationProposal proposal = proposalRepository
                .findByIdInRepositoryWorkspace(
                        context.repositoryId(),
                        proposalId,
                        context.workspaceId())
                .orElseThrow(() -> new ProposalReviewNotFoundException(
                        "Proposal not found in the selected repository/workspace: "
                                + proposalId));
        return new Candidate(
                proposal.getId(),
                proposal.getRepositoryId(),
                proposal.getWorkspaceId(),
                proposal.getSourceNode().getCode(),
                proposal.getTargetNode().getCode(),
                proposal.getRelationType(),
                proposal.getStatus(),
                proposal.getConfidence(),
                proposal.getRationale(),
                proposal.getProvenance(),
                proposal.getReviewBranch(),
                proposal.getReviewCommitId(),
                proposal.getReviewCausationId());
    }

    public record Candidate(
            Long proposalId,
            String repositoryId,
            String workspaceId,
            String sourceCode,
            String targetCode,
            RelationType relationType,
            ProposalStatus status,
            double confidence,
            String rationale,
            String provenance,
            String reviewBranch,
            String reviewCommitId,
            String reviewCausationId) {
        public Candidate {
            proposalId = Objects.requireNonNull(proposalId, "proposalId");
            repositoryId = requireText(repositoryId, "repositoryId");
            sourceCode = requireText(sourceCode, "sourceCode");
            targetCode = requireText(targetCode, "targetCode");
            relationType = Objects.requireNonNull(relationType, "relationType");
            status = Objects.requireNonNull(status, "status");
        }
    }

    public static final class ProposalReviewNotFoundException
            extends IllegalArgumentException {
        public ProposalReviewNotFoundException(String message) {
            super(message);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }
}
