package com.taxonomy.workspace.service;

import java.io.IOException;

/**
 * Workspace-owned access to one exact DSL/Git version, without exposing storage
 * repositories, JGit types or implicit request-context resolution.
 *
 * <p>This is a Git version boundary, not the semantic editor journal. Opening a
 * version does not create an operation, advance a branch or publish a checkpoint.</p>
 *
 * <p>Failed head preconditions are reported as
 * {@link BranchHeadConflictException}; ordinary storage failures remain
 * {@link IOException}. Callers must not treat all I/O failures as conflicts.</p>
 */
public interface WorkspaceDslVersionPort {

    /**
     * Binds one explicitly selected repository/workspace/branch and expected head
     * for the lifetime of a command. A null head means an expected absent branch.
     * The implementation owns the repository handle; callers must not close it.
     */
    ExactVersion openVersion(RepositoryContext context, String expectedHeadCommit) throws IOException;

    interface ExactVersion {
        /** The validated, whitespace-normalized expectation, or null for absence. */
        String expectedHeadCommit();

        /**
         * Reads the immutable expected version, never the latest branch contents.
         * This read alone does not prove that the branch still has that head.
         */
        String readDsl() throws IOException;

        /** Verifies a no-op against the same expected head without adding history. */
        String verifyExpectedHead() throws IOException;

        /**
         * Commits with the bound context's branch/actor and an atomic expected-head
         * precondition. A competing writer is never adopted as an implicit parent.
         */
        CommitResult commit(String dslText, String message) throws IOException;
    }

    record CommitResult(String commitId, String previousHeadCommit) { }
}
