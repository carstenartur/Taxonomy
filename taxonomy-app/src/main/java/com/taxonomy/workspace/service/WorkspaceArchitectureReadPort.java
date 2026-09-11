package com.taxonomy.workspace.service;

import java.io.IOException;

/** Read-only workspace boundary for exact canonical architecture versions. */
public interface WorkspaceArchitectureReadPort {

    /** Exact immutable state visible to read-only workspace consumers. */
    interface State {
        String workspaceScopeKey();
        /** Nullable until a Git checkpoint exists for the semantic workspace state. */
        String commitId();
        long semanticRevision();
    }

    /** Minimal canonical document view exposed outside the workspace bounded context. */
    interface WorkspaceDocument {
        State state();
        String dsl();
    }

    /**
     * Read the current semantic workspace state when {@code commit} is {@code null},
     * or the exact reachable Git checkpoint identified by {@code commit} otherwise.
     */
    WorkspaceDocument read(RepositoryContext context, String commit) throws IOException;
}
