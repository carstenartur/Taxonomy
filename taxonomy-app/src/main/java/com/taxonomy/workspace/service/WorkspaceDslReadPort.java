package com.taxonomy.workspace.service;

import java.io.IOException;
import java.util.Optional;

/**
 * Workspace-owned, read-only access to an explicitly selected repository branch.
 * It exposes neither storage handles nor JGit types and never resolves implicit
 * current-user/current-workspace state.
 */
public interface WorkspaceDslReadPort {

    /** Binds one factory-owned repository handle; the caller must not close it. */
    RepositoryRead openRead(RepositoryContext context);

    interface RepositoryRead {
        /** The selected branch's current full commit ID, or null if absent. */
        String currentHead() throws IOException;

        /**
         * Reads only the exact full commit ID, never the latest branch content.
         * An empty Optional means that the existing commit has no DSL file;
         * Optional.of("") is a valid empty file. Missing commits fail with IOException.
         * Reading a commit does not prove it is still the selected branch head.
         */
        Optional<String> dslAtCommit(String commitId) throws IOException;

        /**
         * Checks the expected head with the existing atomic no-change ref update,
         * without appending history. Null means an expected absent branch.
         */
        String verifyExpectedHead(String expectedHeadCommit) throws IOException;
    }
}
