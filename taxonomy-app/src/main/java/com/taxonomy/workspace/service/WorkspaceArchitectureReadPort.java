package com.taxonomy.workspace.service;

import java.io.IOException;
import java.util.Objects;

/** Read-only workspace boundary for exact canonical architecture versions. */
public interface WorkspaceArchitectureReadPort {

    /** Exact workspace architecture state needed by read-only and mutation consumers. */
    record State(String workspaceScopeKey, String commitId, long semanticRevision) {
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

    /** Minimal canonical document view exposed outside the workspace bounded context. */
    record WorkspaceDocument(State state, String dsl) {
        public WorkspaceDocument {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(dsl, "dsl");
        }
    }

    /**
     * Read the current semantic workspace state when {@code commit} is {@code null},
     * or the exact reachable Git checkpoint identified by {@code commit} otherwise.
     */
    WorkspaceDocument read(RepositoryContext context, String commit) throws IOException;
}
