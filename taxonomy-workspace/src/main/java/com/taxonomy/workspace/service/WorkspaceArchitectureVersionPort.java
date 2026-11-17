package com.taxonomy.workspace.service;

import java.io.IOException;

/** Workspace-owned boundary: checkpoint accepted editing before an explicit version/publish operation. */
public interface WorkspaceArchitectureVersionPort {
    @FunctionalInterface
    interface GitAction<T> { T run() throws IOException; }
    <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException;

    /** Run a reviewed Git write without first checkpointing edits accepted after its preview.
     * The action must compare-and-set the target from expectedHead and return its resulting commit,
     * or null when the Git operation declined to write. */
    default String versionAtHead(RepositoryContext context, String expectedHead, String rationale,
                                 GitAction<String> action) throws IOException {
        return version(context, rationale, action);
    }

    /** Adopt a reviewed personal rebase while the caller holds its workspace and projection rows. */
    default <T> T applyPreparedWorkspaceRebase(RepositoryContext context, String expectedHead, String candidate,
                                               String rationale, GitAction<T> action) throws IOException {
        return applyPreparedVersion(context, expectedHead, candidate, rationale, action);
    }

    /** Apply an already prepared version under the editor journal lock, preserving edits accepted after preparation.
     * Join an existing durable phase so its metadata locks and editor adoption share one transaction. */
    default <T> T applyPreparedVersion(RepositoryContext context, String expectedHead, String candidate, String rationale,
                                       GitAction<T> action) throws IOException {
        return action.run();
    }
}
