package com.taxonomy.relations.service;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RelationCommand;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.UpsertRelation;
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
 * never roll back or hide the already successful Git command. Recovery is
 * persisted in an independent transaction and callers always receive the
 * immutable authority, even when that secondary persistence also fails.</p>
 */
@Service
public class GitAuthoritativeRelationMutationService {

    private final ArchitectureRelationGitCommandService commandService;
    private final RelationDecisionProjectionService projectionService;
    private final RelationProjectionRecoveryService recoveryService;

    public GitAuthoritativeRelationMutationService(
            ArchitectureRelationGitCommandService commandService,
            RelationDecisionProjectionService projectionService,
            RelationProjectionRecoveryService recoveryService) {
        this.commandService = Objects.requireNonNull(
                commandService, "commandService");
        this.projectionService = Objects.requireNonNull(
                projectionService, "projectionService");
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

    public record MutationResult(
            CommandResult authority,
            RelationDecisionProjectionService.ProjectionResult projection) {
        public MutationResult {
            authority = Objects.requireNonNull(authority, "authority");
            projection = Objects.requireNonNull(projection, "projection");
        }
    }

    public static final class ProjectionPendingException
            extends IllegalStateException {
        private final CommandResult authority;
        private final RecoveryRecord recovery;

        public ProjectionPendingException(
                CommandResult authority,
                RecoveryRecord recovery,
                Throwable cause) {
            super("Git relation command succeeded at "
                    + authority.authoritativeCommitId()
                    + ", but its projection requires recovery", cause);
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
