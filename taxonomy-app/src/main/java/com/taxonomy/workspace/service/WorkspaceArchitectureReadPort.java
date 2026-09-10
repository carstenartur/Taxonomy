package com.taxonomy.workspace.service;

import java.io.IOException;

/** Read-only workspace boundary for exact canonical architecture versions. */
public interface WorkspaceArchitectureReadPort {

    /** Read-only projection of exact workspace architecture state. */
    interface ReadState {
        String workspaceScopeKey();

        /** Git checkpoint commit identifier, or {@code null} before a checkpoint exists. */
        String commitId();

        long semanticRevision();
    }

    /** Read-only projection of a canonical architecture document. */
    interface ReadDocument {
        ReadState state();
        String dsl();
    }

    /**
     * Read the current semantic workspace state when {@code commit} is {@code null},
     * or the exact reachable Git checkpoint identified by {@code commit} otherwise.
     */
    ReadDocument read(RepositoryContext context, String commit) throws IOException;
}
