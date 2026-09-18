package com.taxonomy.workspace.service;

import java.io.IOException;

/**
 * Workspace-owned publication of a complete, already constructed DSL snapshot.
 * No storage handles, hypothesis types or transaction callbacks cross this API.
 *
 * <p>This preserves the existing generated-snapshot append operation. It is not
 * an interactive edit command and does not supply an expected-head precondition;
 * caller-reviewed mutations must use {@link WorkspaceDslVersionPort} instead.
 * It neither records semantic editor operations nor publishes editor checkpoints.</p>
 */
public interface WorkspaceDslPublicationPort {

    /**
     * Publishes the exact DSL text in the explicitly selected repository/workspace.
     * The target branch is deliberately explicit and may differ from the branch
     * being viewed in {@code context}; the caller owns that publication policy.
     * The actor is taken from the supplied context, never from thread-local state.
     *
     * <p>The caller owns transaction timing. This method publishes immediately.
     * Callers requiring commit-bound publication must invoke it only after their
     * database transaction commits; immediate callers retain their existing timing.
     * It must not schedule a callback, retry a failed write, or close the
     * factory-owned repository. Empty DSL is a valid full snapshot.</p>
     *
     * @return the published Git commit ID
     * @throws IOException when storage publication fails
     * @throws IllegalStateException when the context is read-only
     */
    String publishSnapshot(RepositoryContext context, String targetBranch,
                           String dslText, String message) throws IOException;
}
