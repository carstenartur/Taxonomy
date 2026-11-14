package com.taxonomy.workspace.service;

import java.io.IOException;

/** Workspace-owned boundary: checkpoint accepted editing before an explicit version/publish operation. */
public interface WorkspaceArchitectureVersionPort {
    @FunctionalInterface
    interface GitAction<T> { T run() throws IOException; }
    <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException;

    /** Apply an already prepared version under the editor journal lock, preserving edits accepted after preparation. */
    default <T> T applyPreparedVersion(RepositoryContext context, String expectedHead, String candidate, String rationale,
                                       GitAction<T> action) throws IOException {
        return action.run();
    }
}
