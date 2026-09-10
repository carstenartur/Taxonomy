package com.taxonomy.workspace.service;

import com.taxonomy.dsl.command.ArchitectureCommand;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Workspace-owned boundary for external integrations that need exact architecture state,
 * durable semantic mutations, and explicit Git checkpoints.
 *
 * <p>The boundary deliberately exposes neither editor persistence nor the concrete editor
 * service. Accepted semantic operations remain durable workspace revisions; checkpoints
 * remain separate, retryable Git versions.</p>
 */
public interface WorkspaceArchitectureIntegrationPort extends WorkspaceArchitectureReadPort {

    /** Validated mutable-workflow state implementing the read-only state contract. */
    record State(String workspaceScopeKey, String commitId, long semanticRevision)
            implements WorkspaceArchitectureReadPort.State {
        public State {
            if (workspaceScopeKey == null || workspaceScopeKey.isBlank()) {
                throw new IllegalArgumentException("Workspace scope key is required");
            }
            workspaceScopeKey = workspaceScopeKey.strip();
            if (semanticRevision < 0) {
                throw new IllegalArgumentException("Semantic revision must not be negative");
            }
            if (commitId != null) {
                commitId = commitId.strip();
                if (commitId.isEmpty()) {
                    commitId = null;
                }
            }
        }
    }

    /** Concrete integration document implementing the read-only document contract. */
    record WorkspaceDocument(State state, String dsl)
            implements WorkspaceArchitectureReadPort.WorkspaceDocument {
        public WorkspaceDocument {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(dsl, "dsl");
        }
    }

    /** Stable command identity and audit metadata without leaking editor-specific DTOs. */
    record CommandMetadata(UUID commandId, UUID correlationId, UUID causationId, String rationale) {
        public CommandMetadata {
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(causationId, "causationId");
            if (rationale == null || rationale.length() > 1000) {
                throw new IllegalArgumentException("A rationale of 1–1000 characters is required");
            }
            rationale = rationale.strip().replaceAll("\\s+", " ");
            if (rationale.isBlank()) {
                throw new IllegalArgumentException("A rationale of 1–1000 characters is required");
            }
            if (rationale.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Rationale must not contain control characters");
            }
        }
    }

    /** Result of an explicit Git checkpoint; no semantic operation is implied. */
    record Checkpoint(State state) {
        public Checkpoint {
            Objects.requireNonNull(state, "state");
        }
    }

    @Override
    WorkspaceDocument read(RepositoryContext context, String commit) throws IOException;

    /** Execute within the workspace model lock used for cross-aggregate integrations. */
    <T> T locked(RepositoryContext context, Function<WorkspaceDocument, T> action) throws IOException;

    /**
     * Append one reviewed integration as a durable semantic revision and prepare its
     * separately retryable checkpoint intent in the same ORM transaction.
     */
    State acceptIntegration(RepositoryContext context, State expected, CommandMetadata metadata,
                            String fingerprint, List<ArchitectureCommand> commands,
                            UnaryOperator<String> portfolioContribution,
                            CommandMetadata checkpointMetadata) throws IOException;

    /** Finish or replay the explicit Git checkpoint for already durable semantic state. */
    Checkpoint checkpoint(RepositoryContext context, State expected,
                          CommandMetadata checkpointMetadata) throws IOException;
}
