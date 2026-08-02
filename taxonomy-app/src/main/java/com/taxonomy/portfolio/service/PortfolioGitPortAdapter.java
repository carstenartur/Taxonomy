package com.taxonomy.portfolio.service;

import com.taxonomy.versioning.service.VersioningPortfolioGitPort;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioGitPort;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Adapter that exposes the portfolio Git application service through ports
 * owned by the workspace and versioning slices.
 */
@Component
public class PortfolioGitPortAdapter
        implements WorkspacePortfolioGitPort, VersioningPortfolioGitPort {

    private final PortfolioGitService portfolioGitService;

    public PortfolioGitPortAdapter(PortfolioGitService portfolioGitService) {
        this.portfolioGitService = portfolioGitService;
    }

    @Override
    public String commitPortfolio(String branch,
                                  String message,
                                  String username,
                                  WorkspaceContext context) throws IOException {
        return portfolioGitService.commit(branch, message, username, context);
    }

    @Override
    public void materializePortfolio(String dsl,
                                     String username,
                                     WorkspaceContext context) {
        portfolioGitService.materialize(dsl, username, context);
    }

    @Override
    public void materializePortfolioHead(String branch,
                                         String username,
                                         WorkspaceContext context) throws IOException {
        portfolioGitService.materializeHead(branch, username, context);
    }
}
