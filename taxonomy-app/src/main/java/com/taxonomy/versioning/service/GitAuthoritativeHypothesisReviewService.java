package com.taxonomy.versioning.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.model.HypothesisStatus;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.model.RelationHypothesis;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.MutationResult;
import com.taxonomy.relations.service.GitAuthoritativeRelationMutationService.ProjectionPendingException;
import com.taxonomy.versioning.service.HypothesisReviewStateStore.HypothesisSnapshot;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Commits accept/reject/revert decisions to the exact selected Git branch before
 * changing relational hypothesis bookkeeping. The surrounding operation is
 * intentionally non-transactional so a successful authority commit survives a
 * later projection or bookkeeping failure.
 */
@Service
public class GitAuthoritativeHypothesisReviewService {

    private static final String HYPOTHESIS_PROVENANCE = "hypothesis-review";

    private final HypothesisReviewStateStore stateStore;
    private final GitAuthoritativeRelationMutationService mutationService;

    public GitAuthoritativeHypothesisReviewService(
            HypothesisReviewStateStore stateStore,
            GitAuthoritativeRelationMutationService mutationService) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.mutationService = Objects.requireNonNull(
                mutationService, "mutationService");
    }

    public ReviewResult accept(
            Long hypothesisId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata) throws IOException {
        return review(
                hypothesisId,
                context,
                expectedHeadCommit,
                metadata,
                ReviewAction.ACCEPT);
    }

    public ReviewResult reject(
            Long hypothesisId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata) throws IOException {
        return review(
                hypothesisId,
                context,
                expectedHeadCommit,
                metadata,
                ReviewAction.REJECT);
    }

    public ReviewResult revert(
            Long hypothesisId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata) throws IOException {
        return review(
                hypothesisId,
                context,
                expectedHeadCommit,
                metadata,
                ReviewAction.REVERT);
    }

    private ReviewResult review(
            Long hypothesisId,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata,
            ReviewAction action) throws IOException {
        RepositoryContext tenant = HypothesisReviewStateStore
                .requireWritableContext(context);
        Objects.requireNonNull(metadata, "metadata");
        HypothesisSnapshot hypothesis = stateStore.require(hypothesisId, tenant);
        action.requireCurrentStatus(hypothesis.id(), hypothesis.status());

        MutationResult mutation;
        try {
            mutation = mutate(
                    hypothesis,
                    tenant,
                    expectedHeadCommit,
                    metadata,
                    action);
        } catch (ProjectionPendingException error) {
            throw HypothesisReviewPendingException.projection(
                    hypothesis.id(),
                    action.targetStatus(),
                    error);
        }

        try {
            RelationHypothesis transitioned = stateStore.transition(
                    hypothesis.id(),
                    tenant,
                    hypothesis.status(),
                    action.targetStatus());
            return new ReviewResult(
                    hypothesis.id(),
                    action,
                    transitioned,
                    mutation);
        } catch (RuntimeException bookkeepingFailure) {
            throw HypothesisReviewPendingException.bookkeeping(
                    hypothesis.id(),
                    action.targetStatus(),
                    mutation.authority(),
                    bookkeepingFailure);
        }
    }

    private MutationResult mutate(
            HypothesisSnapshot hypothesis,
            RepositoryContext context,
            String expectedHeadCommit,
            CommandMetadata metadata,
            ReviewAction action) throws IOException {
        RelationIdentity identity = new RelationIdentity(
                hypothesis.sourceCode(),
                hypothesis.relationType().name(),
                hypothesis.targetCode());
        if (action == ReviewAction.REVERT) {
            return mutationService.remove(
                    context,
                    expectedHeadCommit,
                    identity,
                    metadata);
        }
        return mutationService.upsert(
                context,
                expectedHeadCommit,
                new RelationDefinition(
                        identity,
                        action.dslStatus(),
                        hypothesis.confidence(),
                        HYPOTHESIS_PROVENANCE,
                        Map.of("x-hypothesis-id", hypothesis.id().toString())),
                metadata);
    }

    public enum ReviewAction {
        ACCEPT(HypothesisStatus.ACCEPTED, "accepted"),
        REJECT(HypothesisStatus.REJECTED, "rejected"),
        REVERT(HypothesisStatus.PROVISIONAL, null);

        private final HypothesisStatus targetStatus;
        private final String dslStatus;

        ReviewAction(HypothesisStatus targetStatus, String dslStatus) {
            this.targetStatus = targetStatus;
            this.dslStatus = dslStatus;
        }

        void requireCurrentStatus(Long hypothesisId, HypothesisStatus current) {
            if (this == REVERT) {
                if (current != HypothesisStatus.ACCEPTED
                        && current != HypothesisStatus.REJECTED) {
                    throw new IllegalStateException(
                            "Hypothesis " + hypothesisId
                                    + " cannot be reverted from " + current);
                }
                return;
            }
            if (current != HypothesisStatus.PROVISIONAL
                    && current != HypothesisStatus.PROPOSED) {
                throw new IllegalStateException(
                        "Hypothesis " + hypothesisId
                                + " cannot be " + name().toLowerCase()
                                + " from " + current);
            }
        }

        HypothesisStatus targetStatus() {
            return targetStatus;
        }

        String dslStatus() {
            return dslStatus;
        }
    }

    public record ReviewResult(
            Long hypothesisId,
            ReviewAction action,
            RelationHypothesis hypothesis,
            MutationResult mutation) {
        public ReviewResult {
            hypothesisId = Objects.requireNonNull(hypothesisId, "hypothesisId");
            action = Objects.requireNonNull(action, "action");
            hypothesis = Objects.requireNonNull(hypothesis, "hypothesis");
            mutation = Objects.requireNonNull(mutation, "mutation");
        }
    }

    public static final class HypothesisReviewPendingException
            extends IllegalStateException {
        private final Long hypothesisId;
        private final HypothesisStatus intendedStatus;
        private final CommandResult authority;
        private final PendingPhase phase;

        private HypothesisReviewPendingException(
                Long hypothesisId,
                HypothesisStatus intendedStatus,
                CommandResult authority,
                PendingPhase phase,
                Throwable cause) {
            super("Git hypothesis review succeeded at "
                    + authority.authoritativeCommitId()
                    + ", but " + phase.description + " requires recovery", cause);
            this.hypothesisId = Objects.requireNonNull(
                    hypothesisId, "hypothesisId");
            this.intendedStatus = Objects.requireNonNull(
                    intendedStatus, "intendedStatus");
            this.authority = Objects.requireNonNull(authority, "authority");
            this.phase = Objects.requireNonNull(phase, "phase");
        }

        static HypothesisReviewPendingException projection(
                Long hypothesisId,
                HypothesisStatus intendedStatus,
                ProjectionPendingException error) {
            return new HypothesisReviewPendingException(
                    hypothesisId,
                    intendedStatus,
                    error.getAuthority(),
                    PendingPhase.PROJECTION,
                    error);
        }

        static HypothesisReviewPendingException bookkeeping(
                Long hypothesisId,
                HypothesisStatus intendedStatus,
                CommandResult authority,
                Throwable cause) {
            return new HypothesisReviewPendingException(
                    hypothesisId,
                    intendedStatus,
                    authority,
                    PendingPhase.HYPOTHESIS_BOOKKEEPING,
                    cause);
        }

        public Long getHypothesisId() {
            return hypothesisId;
        }

        public HypothesisStatus getIntendedStatus() {
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
        HYPOTHESIS_BOOKKEEPING("hypothesis bookkeeping");

        private final String description;

        PendingPhase(String description) {
            this.description = description;
        }
    }
}
