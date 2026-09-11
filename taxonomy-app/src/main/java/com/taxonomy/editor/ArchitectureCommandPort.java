package com.taxonomy.editor;

import com.taxonomy.dsl.command.ArchitectureCommand;
import com.taxonomy.dsl.command.ArchitectureDslCommands.Change;
import com.taxonomy.model.WorkspaceOverlayScope;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.RepositoryScope;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Exact-context application boundary. HTTP and canvas DTOs do not enter the domain command handler. */
public interface ArchitectureCommandPort {
    record Context(String repositoryId, String workspaceScopeKey, String branch, String commit, long revision,
                   String actor, String writeMode) {
        public static Context of(RepositoryContext context, String commit) {
            return of(context, commit, 0);
        }
        public static Context version(RepositoryContext context, String commit) {
            return new Context(context.repositoryId(), WorkspaceOverlayScope.keyFor(context.workspaceId()),
                    context.branch(), commit, 0, context.username(), "READ_ONLY");
        }
        public static Context of(RepositoryContext context, String commit, long revision) {
            return new Context(context.repositoryId(), WorkspaceOverlayScope.keyFor(context.workspaceId()),
                    context.branch(), commit, revision, context.username(),
                    context.scope() == RepositoryScope.WORKSPACE ? "PRIVATE_WORKSPACE" : "READ_ONLY");
        }
    }

    sealed interface Operation permits SemanticCommand, UndoArchitectureCommand, RedoArchitectureCommand {}
    record SemanticCommand(ArchitectureCommand command) implements Operation {
        public SemanticCommand { Objects.requireNonNull(command); }
    }
    record UndoArchitectureCommand(UUID targetOperationId) implements Operation {
        public UndoArchitectureCommand { Objects.requireNonNull(targetOperationId); }
    }
    record RedoArchitectureCommand(UUID targetOperationId) implements Operation {
        public RedoArchitectureCommand { Objects.requireNonNull(targetOperationId); }
    }
    record CreateCheckpointCommand(Context context, Metadata metadata) {
        public CreateCheckpointCommand {
            if (context == null || metadata == null) throw new IllegalArgumentException("Checkpoint context and metadata are required");
        }
    }
    record CheckpointAccepted(Context context, String commandId, String commitId,
                              long fromRevision, long throughRevision, boolean commitCreated, boolean replayed) {}

    record Metadata(String commandId, String correlationId, String causationId, String rationale) {
        public Metadata {
            commandId = UUID.fromString(commandId).toString();
            correlationId = UUID.fromString(correlationId).toString();
            causationId = UUID.fromString(causationId).toString();
            if (rationale == null || rationale.isBlank() || rationale.length() > 1000) {
                throw new IllegalArgumentException("A rationale of 1–1000 characters is required");
            }
            rationale = rationale.strip().replaceAll("\\s+", " ");
            if (rationale.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Rationale must not contain control characters");
            }
        }
    }

    record Command(Context context, Metadata metadata, Operation operation) {
        public Command {
            Objects.requireNonNull(context);
            Objects.requireNonNull(metadata);
            Objects.requireNonNull(operation);
        }
    }
    record Preview(Context context, Change change, String kind, String targetOperationId) {}
    record Accepted(Context context, String commandId, String operationId, boolean replayed,
                    String projectionState, Change change) {}

    Preview preview(RepositoryContext context, Command command) throws IOException;
    Accepted execute(RepositoryContext context, Command command) throws IOException;
}
