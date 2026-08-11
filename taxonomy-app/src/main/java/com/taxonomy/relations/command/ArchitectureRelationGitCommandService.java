package com.taxonomy.relations.command;

import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeKind;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.ChangeResult;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationDefinition;
import com.taxonomy.dsl.command.ArchitectureRelationDslTransformer.RelationIdentity;
import com.taxonomy.dsl.storage.DslGitRepository;
import com.taxonomy.dsl.storage.DslGitRepositoryFactory;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter;
import com.taxonomy.dsl.storage.ExpectedHeadDslCommitter.CommitRequest;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * Executes one relation command against the exact repository context and makes
 * the resulting JGit commit the authority token.
 *
 * <p>This service deliberately has no relational repository dependency. A later
 * projection stage may consume {@link CommandResult#authoritativeCommitId()},
 * but a database write can never precede or substitute the Git command here.</p>
 */
@Service
public class ArchitectureRelationGitCommandService {

    private final DslGitRepositoryFactory repositoryFactory;
    private final ArchitectureRelationDslTransformer transformer;
    private final ExpectedHeadDslCommitter committer;

    @Autowired
    public ArchitectureRelationGitCommandService(
            DslGitRepositoryFactory repositoryFactory) {
        this(repositoryFactory,
                new ArchitectureRelationDslTransformer(),
                new ExpectedHeadDslCommitter());
    }

    ArchitectureRelationGitCommandService(
            DslGitRepositoryFactory repositoryFactory,
            ArchitectureRelationDslTransformer transformer,
            ExpectedHeadDslCommitter committer) {
        this.repositoryFactory = Objects.requireNonNull(
                repositoryFactory, "repositoryFactory");
        this.transformer = Objects.requireNonNull(transformer, "transformer");
        this.committer = Objects.requireNonNull(committer, "committer");
    }

    /**
     * Applies and commits one command using only the supplied request-stable
     * repository context.
     */
    public CommandResult execute(
            RepositoryContext context,
            String expectedHeadCommit,
            RelationCommand command) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");
        requireMutable(context);
        String normalizedExpectedHead = normalizeExpectedHead(expectedHeadCommit);

        DslGitRepository repository = repositoryFactory.resolveRepository(context);
        String sourceDsl = normalizedExpectedHead == null
                ? ""
                : repository.getDslAtCommit(normalizedExpectedHead);
        if (sourceDsl == null) {
            sourceDsl = "";
        }

        ChangeResult change = apply(sourceDsl, command);
        if (!change.changed()) {
            String authoritativeHead = committer.verifyExpectedHead(
                    repository,
                    context.branch(),
                    normalizedExpectedHead);
            return result(
                    context,
                    normalizedExpectedHead,
                    authoritativeHead,
                    change.kind(),
                    false,
                    command.metadata().causationId());
        }

        ExpectedHeadDslCommitter.CommitResult commit = committer.commit(
                repository,
                new CommitRequest(
                        context.branch(),
                        normalizedExpectedHead,
                        change.dsl(),
                        context.username(),
                        commitMessage(command, change.kind())));
        return result(
                context,
                commit.previousHeadCommit(),
                commit.commitId(),
                change.kind(),
                true,
                command.metadata().causationId());
    }

    private ChangeResult apply(String sourceDsl, RelationCommand command) {
        if (command instanceof UpsertRelation upsert) {
            return transformer.upsert(sourceDsl, upsert.definition());
        }
        if (command instanceof RemoveRelation remove) {
            return transformer.remove(sourceDsl, remove.identity());
        }
        throw new IllegalArgumentException(
                "Unsupported relation command: " + command.getClass().getName());
    }

    private String commitMessage(RelationCommand command, ChangeKind changeKind) {
        RelationIdentity identity = command.identity();
        StringBuilder message = new StringBuilder("relation: ")
                .append(changeKind.name().toLowerCase(Locale.ROOT))
                .append(' ')
                .append(identity.sourceId()).append(' ')
                .append(identity.relationType()).append(' ')
                .append(identity.targetId())
                .append("\n\nCausation-Id: ")
                .append(command.metadata().causationId());
        if (command.metadata().rationale() != null) {
            message.append("\nRationale: ")
                    .append(command.metadata().rationale());
        }
        return message.toString();
    }

    private CommandResult result(
            RepositoryContext context,
            String previousHead,
            String authoritativeHead,
            ChangeKind changeKind,
            boolean commitCreated,
            String causationId) {
        return new CommandResult(
                context.repositoryId(),
                context.workspaceId(),
                context.branch(),
                context.scope(),
                previousHead,
                authoritativeHead,
                changeKind,
                commitCreated,
                causationId);
    }

    private static void requireMutable(RepositoryContext context) {
        if (context.scope() == RepositoryScope.CENTRAL_READ) {
            throw new ReadOnlyRepositoryContextException(
                    "Relation commands require CENTRAL_WRITE, WORKSPACE or FORK scope");
        }
    }

    private static String normalizeExpectedHead(String expectedHeadCommit) {
        if (expectedHeadCommit == null) {
            return null;
        }
        String normalized = expectedHeadCommit.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "expectedHeadCommit must be null or a full commit ID");
        }
        ObjectId.fromString(normalized);
        return normalized;
    }

    public sealed interface RelationCommand
            permits UpsertRelation, RemoveRelation {
        RelationIdentity identity();
        CommandMetadata metadata();
    }

    public record UpsertRelation(
            RelationDefinition definition,
            CommandMetadata metadata) implements RelationCommand {
        public UpsertRelation {
            definition = Objects.requireNonNull(definition, "definition");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }

        @Override
        public RelationIdentity identity() {
            return definition.identity();
        }
    }

    public record RemoveRelation(
            RelationIdentity identity,
            CommandMetadata metadata) implements RelationCommand {
        public RemoveRelation {
            identity = Objects.requireNonNull(identity, "identity");
            metadata = Objects.requireNonNull(metadata, "metadata");
        }
    }

    public record CommandMetadata(
            String causationId,
            String rationale) {
        public CommandMetadata {
            causationId = requireSingleLine(causationId, "causationId");
            rationale = normalizeRationale(rationale);
        }

        public CommandMetadata(String causationId) {
            this(causationId, null);
        }
    }

    public record CommandResult(
            String repositoryId,
            String workspaceId,
            String branch,
            RepositoryScope scope,
            String previousHeadCommit,
            String authoritativeCommitId,
            ChangeKind changeKind,
            boolean commitCreated,
            String causationId) {
    }

    public static final class ReadOnlyRepositoryContextException
            extends IllegalStateException {
        public ReadOnlyRepositoryContextException(String message) {
            super(message);
        }
    }

    private static String requireSingleLine(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " must be one line");
        }
        return normalized;
    }

    private static String normalizeRationale(String rationale) {
        if (rationale == null || rationale.isBlank()) {
            return null;
        }
        return rationale.strip().replaceAll("\\s+", " ");
    }
}
