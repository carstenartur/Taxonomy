package com.taxonomy.workspace.service;

import java.io.IOException;

/** Read-only workspace boundary for exact canonical architecture versions. */
public interface WorkspaceArchitectureReadPort {

    /** Read-only projection of exact workspace architecture state. */
    interface ReadState {
        String workspaceScopeKey();
        String commitId();
        long semanticRevision();
    }

    /** Read-only projection of a canonical architecture document. */
    interface ReadDocument {
        ReadState state();
        String dsl();
    }

    ReadDocument read(RepositoryContext context, String commit) throws IOException;
}
