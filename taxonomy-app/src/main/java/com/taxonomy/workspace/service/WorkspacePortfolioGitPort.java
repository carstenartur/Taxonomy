package com.taxonomy.workspace.service;

import java.io.IOException;

/**
 * Workspace-owned port for projecting and materializing the durable project
 * portfolio during pull and publish operations.
 *
 * <p>The interface deliberately lives in the calling workspace slice. Its
 * implementation is provided by the portfolio slice, preventing the workspace
 * domain from depending on portfolio implementation classes.</p>
 */
public interface WorkspacePortfolioGitPort {

    String commitPortfolio(String branch,
                           String message,
                           String username,
                           WorkspaceContext context) throws IOException;

    void materializePortfolio(String dsl,
                              String username,
                              WorkspaceContext context);

    void materializePortfolioHead(String branch,
                                  String username,
                                  WorkspaceContext context) throws IOException;
}
