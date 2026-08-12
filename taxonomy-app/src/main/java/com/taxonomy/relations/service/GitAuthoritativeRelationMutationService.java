package com.taxonomy.relations.service;

import com.taxonomy.relations.command.ArchitectureRelationGitCommandService;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandMetadata;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.CommandResult;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RelationCommand;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.RemoveRelation;
import com.taxonomy.relations.command.ArchitectureRelationGitCommandService.UpsertRelation;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.workspace.service.RepositoryContext;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Objects;

/**
 * Makes one exact Git commit authoritative before attempting its rebuildable
 * relational projection.
 *
 * <p>The method is deliberately not transactional: a failed projection must
 * never roll back or hide the already successful Git command. Callers receive
 * the authoritative commit through {@link ProjectionPendingException} and can
 * report an accepted/pending-rebuild outcome.</p>
 */
@Service
public class GitAuthoritativeRelationMutationService {

    private final ArchitectureRelationGitCommandService commandService;
    private final RelationDecisionProjectionService projectionService;

    public GitAuthoritativeRelationMutationService(
            ArchitectureRelationGitCommandService commandService,
            RelationDecisionProjectionService projectionService) {
        this.commandService = Objects.requireNonNull(
                commandService, "commandService");
        this.projectionService = Objects.requireNonNull(
                projectionService, "projectionService");
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
            throw new ProjectionPendingException(authority, projectionFailure);
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

        public ProjectionPendingException(
                CommandResult authority,
                Throwable cause) {
            super("Git relation command succeeded at "
                    + authority.authoritativeCommitId()
                    + ", but its projection requires recovery", cause);
            this.authority = Objects.requireNonNull(authority, "authority");
        }

        public CommandResult getAuthority() {
            return authority;
        }
    }
}
