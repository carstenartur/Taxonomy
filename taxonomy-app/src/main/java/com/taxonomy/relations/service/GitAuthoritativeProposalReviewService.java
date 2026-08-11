package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.RelationProposalReviewCandidateReader.Candidate;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Commits a proposal decision to the exact Git branch before projecting the
 * relation and proposal status.
 */
@Service
public class GitAuthoritativeProposalReviewService {

    private final RelationProposalReviewCandidateReader candidateReader;
    private final GitAuthoritativeRelationMutationService relationMutationService;
    private final ProposalReviewProjectionWriter proposalProjectionWriter;

    public GitAuthoritativeProposalReviewService(
            RelationProposalReviewCandidateReader candidateReader,
            GitAuthoritativeRelationMutationService relationMutationService,
            ProposalReviewProjectionWriter proposalProjectionWriter) {
        this.candidateReader = Objects.requireNonNull(
                candidateReader, "candidateReader");
        this.relationMutationService = Objects.requireNonNull(
                relationMutationService, "relationMutationService");
        this.proposalProjectionWriter = Objects.requireNonNull(
                proposalProjectionWriter, "proposalProjectionWriter");
    }

    public ReviewResult review(
            RepositoryContext context,
            Long proposalId,
            String expectedHeadCommit,
            String causationId,
            ProposalReviewDecision decision,
            String reviewRationale) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(decision, "decision");
        Candidate candidate = candidateReader.read(context, proposalId);
        requireReplayCompatible(candidate, context, decision, causationId);

        RelationDefinition definition = new RelationDefinition(
                new RelationIdentity(
                        candidate.sourceCode(),
                        candidate.relationType().name(),
                        candidate.targetCode()),
                decision.dslStatus(),
                candidate.confidence(),
                normalizeOptional(candidate.provenance()),
                reviewExtensions(candidate, decision));
        String rationale = normalizeOptional(reviewRationale);
        if (rationale == null) {
            rationale = normalizeOptional(candidate.rationale());
        }

        MutationResult relationResult = relationMutationService.upsert(
                context,
                expectedHeadCommit,
                definition,
                new CommandMetadata(causationId, rationale));
        CommandResult authority = relationResult.authority();
        try {
            ProposalReviewProjectionWriter.ProjectionResult proposalProjection =
                    proposalProjectionWriter.project(
                            new ProposalReviewProjectionWriter.ProjectionRequest(
                                    candidate.proposalId(),
                                    context.repositoryId(),
                                    context.workspaceId(),
                                    context.branch(),
                                    candidate.sourceCode(),
                                    candidate.targetCode(),
                                    candidate.relationType(),
                                    decision,
                                    authority.authoritativeCommitId(),
                                    authority.causationId()));
            return new ReviewResult(
                    decision,
                    authority,
                    relationResult.projection(),
                    proposalProjection);
        } catch (RuntimeException projectionFailure) {
            throw new ProposalReviewProjectionPendingException(
                    candidate.proposalId(), authority, projectionFailure);
        }
    }

    private static Map<String, String> reviewExtensions(
            Candidate candidate,
            ProposalReviewDecision decision) {
        Map<String, String> extensions = new LinkedHashMap<>();
        extensions.put(
                "x-source-proposal-id",
                String.valueOf(candidate.proposalId()));
        extensions.put("x-review-decision", decision.dslStatus());
        return Map.copyOf(extensions);
    }

    private static void requireReplayCompatible(
            Candidate candidate,
            RepositoryContext context,
            ProposalReviewDecision decision,
            String causationId) {
        if (candidate.status() == ProposalStatus.PENDING) {
            return;
        }
        if (candidate.status() == decision.proposalStatus()
                && context.branch().equals(candidate.reviewBranch())
                && normalizeOptional(causationId) != null
                && causationId.strip().equals(candidate.reviewCausationId())
                && normalizeOptional(candidate.reviewCommitId()) != null) {
            return;
        }
        throw new ProposalReviewConflictException(
                "Proposal " + candidate.proposalId()
                        + " is already reviewed with another decision, branch or causation ID");
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record ReviewResult(
            ProposalReviewDecision decision,
            CommandResult authority,
            RelationDecisionProjectionService.ProjectionResult relationProjection,
            ProposalReviewProjectionWriter.ProjectionResult proposalProjection) {
        public ReviewResult {
            decision = Objects.requireNonNull(decision, "decision");
            authority = Objects.requireNonNull(authority, "authority");
            relationProjection = Objects.requireNonNull(
                    relationProjection, "relationProjection");
            proposalProjection = Objects.requireNonNull(
                    proposalProjection, "proposalProjection");
        }
    }

    public static final class ProposalReviewConflictException
            extends IllegalStateException {
        public ProposalReviewConflictException(String message) {
            super(message);
        }
    }

    public static final class ProposalReviewProjectionPendingException
            extends IllegalStateException {
        private final Long proposalId;
        private final CommandResult authority;

        public ProposalReviewProjectionPendingException(
                Long proposalId,
                CommandResult authority,
                Throwable cause) {
            super("Proposal " + proposalId
                    + " has authoritative Git review commit "
                    + authority.authoritativeCommitId()
                    + ", but its proposal projection requires recovery",
                    cause);
            this.proposalId = proposalId;
            this.authority = authority;
        }

        public Long getProposalId() {
            return proposalId;
        }

        public CommandResult getAuthority() {
            return authority;
        }
    }
}
