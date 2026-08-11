package com.taxonomy.relations.service;

import com.taxonomy.model.ProposalStatus;
import com.taxonomy.model.RelationType;
import com.taxonomy.relations.model.RelationProposal;
import com.taxonomy.relations.repository.RelationProposalReviewRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/** Transactional proposal-status projection after an exact Git review commit exists. */
@Service
public class ProposalReviewProjectionWriter {

    private final RelationProposalReviewRepository proposalRepository;
    private final Clock clock;

    public ProposalReviewProjectionWriter(
            RelationProposalReviewRepository proposalRepository) {
        this(proposalRepository, Clock.systemUTC());
    }

    ProposalReviewProjectionWriter(
            RelationProposalReviewRepository proposalRepository,
            Clock clock) {
        this.proposalRepository = Objects.requireNonNull(
                proposalRepository, "proposalRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Transactional
    public ProjectionResult project(ProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        RelationProposal proposal = proposalRepository.findExactForUpdate(
                        request.repositoryId(),
                        request.workspaceId(),
                        request.proposalId())
                .orElseThrow(() -> new ProposalReviewProjectionException(
                        "Proposal disappeared from the selected tenant before projection: "
                                + request.proposalId()));
        requireIdentity(proposal, request);

        if (proposal.getStatus() == ProposalStatus.PENDING) {
            proposal.setStatus(request.decision().proposalStatus());
            proposal.setReviewedAt(Instant.now(clock));
            proposal.setReviewBranch(request.branch());
            proposal.setReviewCommitId(request.authoritativeCommitId());
            proposal.setReviewCausationId(request.causationId());
            proposalRepository.save(proposal);
            proposalRepository.flush();
            return result(ProjectionOutcome.UPDATED, proposal);
        }

        if (proposal.getStatus() == request.decision().proposalStatus()
                && request.branch().equals(proposal.getReviewBranch())
                && request.authoritativeCommitId().equals(
                        proposal.getReviewCommitId())
                && request.causationId().equals(
                        proposal.getReviewCausationId())) {
            return result(ProjectionOutcome.REPLAYED, proposal);
        }

        throw new ProposalReviewConflictException(
                "Proposal " + proposal.getId()
                        + " is already reviewed with a different decision or authority commit");
    }

    private static void requireIdentity(
            RelationProposal proposal,
            ProjectionRequest request) {
        if (!request.repositoryId().equals(proposal.getRepositoryId())
                || !Objects.equals(request.workspaceId(), proposal.getWorkspaceId())
                || !request.sourceCode().equals(
                        proposal.getSourceNode().getCode())
                || !request.targetCode().equals(
                        proposal.getTargetNode().getCode())
                || request.relationType() != proposal.getRelationType()) {
            throw new ProposalReviewConflictException(
                    "Proposal identity changed after the Git review command");
        }
    }

    private static ProjectionResult result(
            ProjectionOutcome outcome,
            RelationProposal proposal) {
        return new ProjectionResult(
                outcome,
                proposal.getId(),
                proposal.getStatus(),
                proposal.getReviewBranch(),
                proposal.getReviewCommitId(),
                proposal.getReviewCausationId(),
                proposal.getReviewedAt());
    }

    public record ProjectionRequest(
            Long proposalId,
            String repositoryId,
            String workspaceId,
            String branch,
            String sourceCode,
            String targetCode,
            RelationType relationType,
            ProposalReviewDecision decision,
            String authoritativeCommitId,
            String causationId) {
        public ProjectionRequest {
            proposalId = Objects.requireNonNull(proposalId, "proposalId");
            repositoryId = requireText(repositoryId, "repositoryId");
            branch = requireText(branch, "branch");
            sourceCode = requireText(sourceCode, "sourceCode");
            targetCode = requireText(targetCode, "targetCode");
            relationType = Objects.requireNonNull(relationType, "relationType");
            decision = Objects.requireNonNull(decision, "decision");
            authoritativeCommitId = requireCommit(
                    authoritativeCommitId, "authoritativeCommitId");
            causationId = requireText(causationId, "causationId");
        }
    }

    public enum ProjectionOutcome {
        UPDATED,
        REPLAYED
    }

    public record ProjectionResult(
            ProjectionOutcome outcome,
            Long proposalId,
            ProposalStatus status,
            String branch,
            String authoritativeCommitId,
            String causationId,
            Instant reviewedAt) {
        public ProjectionResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            proposalId = Objects.requireNonNull(proposalId, "proposalId");
            status = Objects.requireNonNull(status, "status");
            branch = requireText(branch, "branch");
            authoritativeCommitId = requireCommit(
                    authoritativeCommitId, "authoritativeCommitId");
            causationId = requireText(causationId, "causationId");
            reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
        }
    }

    public static class ProposalReviewProjectionException
            extends IllegalStateException {
        public ProposalReviewProjectionException(String message) {
            super(message);
        }
    }

    public static final class ProposalReviewConflictException
            extends ProposalReviewProjectionException {
        public ProposalReviewConflictException(String message) {
            super(message);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.strip();
    }

    private static String requireCommit(String value, String field) {
        try {
            return ObjectId.fromString(value).name();
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IllegalArgumentException(
                    field + " must be a full Git object ID", error);
        }
    }
}
