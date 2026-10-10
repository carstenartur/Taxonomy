package com.taxonomy.versioning.service;

import com.taxonomy.workspace.service.WorkspaceContext;

import java.io.IOException;

/**
 * Versioning-owned port for rebuilding the portfolio projection after a Git
 * merge. The versioning slice depends only on this contract, not on portfolio
 * implementation classes.
 */
public interface VersioningPortfolioGitPort {

    /** Check before accepting a merge whose projection needs this feature. */
    default void requireAvailable() { }

    void materializePortfolioHead(String branch,
                                  String username,
                                  WorkspaceContext context) throws IOException;
}
