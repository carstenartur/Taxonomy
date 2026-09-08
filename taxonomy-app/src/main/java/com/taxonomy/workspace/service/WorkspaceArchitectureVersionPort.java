package com.taxonomy.workspace.service;

import java.io.IOException;

/** Workspace-owned boundary: checkpoint accepted editing before an explicit version/publish operation. */
public interface WorkspaceArchitectureVersionPort {
    @FunctionalInterface
    interface GitAction<T> { T run() throws IOException; }
    <T> T version(RepositoryContext context, String rationale, GitAction<T> action) throws IOException;
}
