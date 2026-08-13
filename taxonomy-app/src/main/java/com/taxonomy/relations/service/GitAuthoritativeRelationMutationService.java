package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RelationCommand;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.UpsertRelation;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.Readiness;
import com.taxonomy.relations.service.RelationBranchProjectionReadinessService.ReadinessState;
import com.taxonomy.relations.service.RelationBranchProjectionRebuildService.RebuildResult;
import com.taxonomy.relations.service.RelationProjectionRecoveryService.RecoveryRecord;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;

/**
 * Makes one exact Git commit authoritative before attempting its rebuildable
 * relational projection.
 *
 * <p>The method is deliberately not transactional: a failed projection must
 * never roll back or hide the already successful Git command. The exact command
 * state is projected first, then the complete branch is rebuilt and proven ready
 * before any pending recovery is reconciled. Recovery is persisted in an
 * independent transaction and callers always receive the immutable Git authority,
 * even when that secondary persistence also fails.</p>
 */
@Service
public class GitAuthoritativeRelationMutationService {

    private final ArchitectureRelationGitCommandService commandService;
    private final RelationDecisionProjectionService projectionService;
    private final RelationBranchProjectionRebuildService rebuildService;
    private final RelationBranchProjectionReadinessService readinessService;
    private final RelationProjectionRecoveryService recoveryService;

    public GitAuthoritativeRelationMutationService(
            ArchitectureRelationGitCommandService commandService,
            RelationDecisionProjectionService projectionService,
            RelationBranchProjectionRebuildService rebuildService,
            RelationBranchProjectionReadinessService readinessService,
            RelationProjectionRecoveryService recoveryService) {
        this.commandService = Objects.requireNonNull(
                commandService, "commandService");
        this.projectionService = Objects.requireNonNull(
                projectionService, "projectionService");
        this.rebuildService = Objects.requireNonNull(
                rebuildService, "rebuildService");
        this.readinessService = Objects.requireNonNull(
                readinessService, "readinessService");
        this.recoveryService = Objects.requireNonNull(
                recoveryService, "recoveryService");
    }

    public MutationResult upsert(
            RepositoryContext context,
            String expectedHeadCommit,
            RelationDefinition definition,
            CommandMetadata metadata) throws IOException {
        return execute(
                context,
                expectedHeadCommit,
                new UpsertRelation(definition, metadata));
    }

    public MutationResult remove(
            RepositoryContext context,
            String expectedHeadCommit,
            RelationIdentity identity,
            CommandMetadata metadata) throws IOException {
        return execute(
                context,
                expectedHeadCommit,
                new RemoveRelation(identity, metadata));
    }

    private MutationResult execute(
            RepositoryContext context,
            String expectedHeadCommit,
            RelationCommand command) throws IOException {
        CommandResult authority = commandService.execute(
                context, expectedHeadCommit, command);
        try {
            RelationDecisionProjectionService.ProjectionResult projection =
                    projectionService.project(context, authority, command);
            RebuildResult rebuild = rebuildService.rebuild(context);
            verifyCompleteProjection(context, authority, rebuild);
            recoveryService.reconcileAfterRebuild(
                    context, authority.authoritativeCommitId());
            return new MutationResult(authority, projection);
        } catch (RuntimeException projectionFailure) {
            RecoveryRecord recovery = null;
            try {
                recovery = recoveryService.recordPending(
                        authority, projectionFailure);
            } catch (RuntimeException recoveryFailure) {
                projectionFailure.addSuppressed(recoveryFailure);
            }
            throw new ProjectionPendingException(
                    authority, recovery, projectionFailure);
        }
    }

    private void verifyCompleteProjection(
            RepositoryContext context,
            CommandResult authority,
            RebuildResult rebuild) {
        String authoritativeCommit = authority.authoritativeCommitId();
        if (!authoritativeCommit.equals(rebuild.authoritativeCommitId())) {
            throw new ProjectionCompletionException(
                    "Complete relation projection rebuilt commit "
                            + rebuild.authoritativeCommitId()
                            + " instead of command authority "
                            + authoritativeCommit);
        }

        Readiness readiness = readinessService.inspect(context);
        boolean complete = readiness.state() == ReadinessState.READY
                && authoritativeCommit.equals(readiness.currentHeadCommit())
                && authoritativeCommit.equals(readiness.projectedCommit())
                && rebuild.relationCount() == readiness.rows().size();
        if (!complete) {
            throw new ProjectionCompletionException(
                    "Relation command at " + authoritativeCommit
                            + " did not produce a complete readable branch projection: "
                            + readiness.state());
        }
    }

    public record MutationResult(
            CommandResult authority,
            RelationDecisionProjectionService.ProjectionResult projection) {
        public MutationResult {
            authority = Objects.requireNonNull(authority, "authority");
            projection = Objects.requireNonNull(projection, "projection");
        }
    }

    public static final class ProjectionCompletionException
            extends IllegalStateException {
        public ProjectionCompletionException(String message) {
            super(message);
        }
    }

    public static final class ProjectionPendingException
            extends IllegalStateException {
        private final CommandResult authority;
        private final RecoveryRecord recovery;

        /**
         * Compatibility form for callers that know the Git authority but do
         * not have a durable recovery record, for example isolated tests or a
         * secondary recovery-store failure.
         */
        public ProjectionPendingException(
                CommandResult authority,
                Throwable cause) {
            this(authority, null, cause);
        }

        public ProjectionPendingException(
                CommandResult authority,
                RecoveryRecord recovery,
                Throwable cause) {
            super("Git relation command succeeded at "
                    + authority.authoritativeCommitId()
                    + ", but its complete projection requires recovery", cause);
            this.authority = Objects.requireNonNull(authority, "authority");
            this.recovery = recovery;
        }

        public CommandResult getAuthority() {
            return authority;
        }

        public RecoveryRecord getRecovery() {
            return recovery;
        }

        public boolean isRecoveryPersisted() {
            return recovery != null;
        }
    }
}
