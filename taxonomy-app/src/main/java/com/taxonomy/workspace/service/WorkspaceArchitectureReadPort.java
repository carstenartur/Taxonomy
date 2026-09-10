package com.taxonomy.workspace.service;

import java.io.IOException;
import java.util.Objects;

/** Read-only workspace boundary for exact canonical architecture versions. */
public interface WorkspaceArchitectureReadPort {

    /** Exact workspace architecture state needed to identify a canonical version. */
    record State(String workspaceScopeKey, String commitId, long semanticRevision) {
        public State {
            if (workspaceScopeKey == null || workspaceScopeKey.isBlank()) {
                throw new IllegalArgumentException("Workspace scope key is required");
            }
            if (semanticRevision < 0) {
                throw new IllegalArgumentException("Semantic revision must not be negative");
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

    WorkspaceDocument read(RepositoryContext context, String commit) throws IOException;
}
