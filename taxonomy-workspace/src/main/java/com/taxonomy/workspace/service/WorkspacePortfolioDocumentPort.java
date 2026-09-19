package com.taxonomy.workspace.service;

import java.io.IOException;
import java.util.List;

/** Captures the selected repository once; portfolio sees no JGit/Hibernate storage implementation. */
public interface WorkspacePortfolioDocumentPort {
    DocumentHandle resolveRepository(WorkspaceContext context) throws IOException;

    interface DocumentHandle {
        String getDslAtHead(String branch) throws IOException;
        /** Reads immutable content without resolving a mutable branch again. */
        String getDslAtCommit(String commitId) throws IOException;
        String getHeadCommit(String branch) throws IOException;
        String commitDsl(String branch, String dsl, String author, String message) throws IOException;
        MergeResult mergeBranches(String fromBranch, String intoBranch, String author, String message) throws IOException;
    }

    record MergeResult(boolean success, String commitId, boolean semanticFallback, List<String> conflicts) {}
}
