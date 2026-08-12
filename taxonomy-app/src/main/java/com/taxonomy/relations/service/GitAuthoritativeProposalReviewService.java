package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.model.ProposalStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.relations.service.ProposalReviewStateStore.ProposalSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Reviews one proposal by committing the relation decision to Git first and
 * advancing relational proposal bookkeeping only after projection succeeds.
 */
@Service
public class GitAuthoritativeProposalReviewService {

    private static final String PROPOSAL_PROVENANCE = "proposal-review";

    private final ProposalReviewStateStore stateStore;
    private final GitAuthoritativeRelationMutationService mutationService;

    public GitAuthoritativeProposalReviewService(
            ProposalReviewStateStore stateStore,
            GitAuthoritativeRelationMutationService mutationService) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.mutationService = Objects.requireNonNull(
                mutationService, "mutationService");
    }

    public ReviewResult accept(
            Long proposalId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata) throws IOException {
        return review(
                proposalId,
                context,
                expectedHeadCommit,
                metadata,
                ReviewAction.ACCEPT);
    }

    public ReviewResult reject(
            Long proposalId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata) throws IOException {
        return review(
                proposalId,
                context,
                expectedHeadCommit,
                metadata,
                ReviewAction.REJECT);
    }

    public ReviewResult revert(
            Long proposalId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata) throws IOException {
        return review(
                proposalId,
                context,
                expectedHeadCommit,
                metadata,
                ReviewAction.REVERT);
    }

    private ReviewResult review(
            Long proposalId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata,
            ReviewAction action) throws IOException {
        RepositoryContext tenant = ProposalReviewStateStore
                .requireWritableContext(context);
        Objects.requireNonNull(metadata, "metadata");
        ProposalSnapshot proposal = stateStore.require(proposalId, tenant);
        action.requireCurrentStatus(proposal.id(), proposal.status());

        MutationResult mutation;
        try {
            mutation = mutate(
                    proposal,
                    tenant,
                    expectedHeadCommit,
                    metadata,
                    action);
        } catch (ProjectionPendingException error) {
            throw ProposalReviewPendingException.projection(
                    proposal.id(), action.targetStatus(), error);
        }

        try {
            ProposalStatus status = stateStore.transition(
                    proposal.id(),
                    tenant,
                    proposal.status(),
                    action.targetStatus());
            return new ReviewResult(
                    proposal.id(), action, status, mutation);
        } catch (RuntimeException bookkeepingFailure) {
            throw ProposalReviewPendingException.bookkeeping(
                    proposal.id(),
                    action.targetStatus(),
                    mutation.authority(),
                    bookkeepingFailure);
        }
    }

    private MutationResult mutate(
            ProposalSnapshot proposal,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata,
            ReviewAction action) throws IOException {
        RelationIdentity identity = new RelationIdentity(
                proposal.sourceCode(),
                proposal.relationType().name(),
                proposal.targetCode());
        if (action == ReviewAction.REVERT) {
            return mutationService.remove(
                    context, expectedHeadCommit, identity, metadata);
        }
        return mutationService.upsert(
                context,
                expectedHeadCommit,
                new RelationDefinition(
                        identity,
                        action.dslStatus(),
                        proposal.confidence(),
                        PROPOSAL_PROVENANCE,
                        Map.of(
                                "x-proposal-id",
                                proposal.id().toString())),
                metadata);
    }

    public enum ReviewAction {
        ACCEPT(ProposalStatus.PENDING, ProposalStatus.ACCEPTED, "accepted"),
        REJECT(ProposalStatus.PENDING, ProposalStatus.REJECTED, "rejected"),
        REVERT(null, ProposalStatus.PENDING, null);

        private final ProposalStatus requiredStatus;
        private final ProposalStatus targetStatus;
        private final String dslStatus;

        ReviewAction(
                ProposalStatus requiredStatus,
                ProposalStatus targetStatus,
                String dslStatus) {
            this.requiredStatus = requiredStatus;
            this.targetStatus = targetStatus;
            this.dslStatus = dslStatus;
        }

        void requireCurrentStatus(Long proposalId, ProposalStatus current) {
            if (this == REVERT) {
                if (current == ProposalStatus.PENDING) {
                    throw new IllegalStateException(
                            "Proposal " + proposalId + " is already PENDING");
                }
                return;
            }
            if (current != requiredStatus) {
                throw new IllegalStateException(
                        "Proposal " + proposalId + " is already " + current);
            }
        }

        ProposalStatus targetStatus() {
            return targetStatus;
        }

        String dslStatus() {
            return dslStatus;
        }
    }

    public record ReviewResult(
            Long proposalId,
            ReviewAction action,
            ProposalStatus proposalStatus,
            MutationResult mutation) {
        public ReviewResult {
            proposalId = Objects.requireNonNull(proposalId, "proposalId");
            action = Objects.requireNonNull(action, "action");
            proposalStatus = Objects.requireNonNull(
                    proposalStatus, "proposalStatus");
            mutation = Objects.requireNonNull(mutation, "mutation");
        }
    }

    public static final class ProposalReviewPendingException
            extends IllegalStateException {
        private final Long proposalId;
        private final ProposalStatus intendedStatus;
        private final CommandResult authority;
        private final PendingPhase phase;

        private ProposalReviewPendingException(
                Long proposalId,
                ProposalStatus intendedStatus,
                CommandResult authority,
                PendingPhase phase,
                Throwable cause) {
            super("Git proposal review succeeded at "
                    + authority.authoritativeCommitId()
                    + ", but " + phase.description + " requires recovery", cause);
            this.proposalId = Objects.requireNonNull(proposalId, "proposalId");
            this.intendedStatus = Objects.requireNonNull(
                    intendedStatus, "intendedStatus");
            this.authority = Objects.requireNonNull(authority, "authority");
            this.phase = Objects.requireNonNull(phase, "phase");
        }

        static ProposalReviewPendingException projection(
                Long proposalId,
                ProposalStatus intendedStatus,
                ProjectionPendingException error) {
            return new ProposalReviewPendingException(
                    proposalId,
                    intendedStatus,
                    error.getAuthority(),
                    PendingPhase.PROJECTION,
                    error);
        }

        static ProposalReviewPendingException bookkeeping(
                Long proposalId,
                ProposalStatus intendedStatus,
                CommandResult authority,
                Throwable cause) {
            return new ProposalReviewPendingException(
                    proposalId,
                    intendedStatus,
                    authority,
                    PendingPhase.PROPOSAL_BOOKKEEPING,
                    cause);
        }

        public Long getProposalId() {
            return proposalId;
        }

        public ProposalStatus getIntendedStatus() {
            return intendedStatus;
        }

        public CommandResult getAuthority() {
            return authority;
        }

        public PendingPhase getPhase() {
            return phase;
        }
    }

    public enum PendingPhase {
        PROJECTION("relation projection"),
        PROPOSAL_BOOKKEEPING("proposal bookkeeping");

        private final String description;

        PendingPhase(String description) {
            this.description = description;
        }
    }
}
