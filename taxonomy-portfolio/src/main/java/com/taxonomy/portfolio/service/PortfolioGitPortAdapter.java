package com.taxonomy.portfolio.service;

import com.taxonomy.versioning.service.VersioningPortfolioGitPort;
import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioGitPort;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Adapter that exposes the portfolio Git application service through ports
 * owned by the workspace and versioning slices.
 */
@Component
public class PortfolioGitPortAdapter
        implements WorkspacePortfolioGitPort, VersioningPortfolioGitPort {

    private final PortablePortfolioGitService portfolioGitService;

    public PortfolioGitPortAdapter(PortablePortfolioGitService portfolioGitService) {
        this.portfolioGitService = portfolioGitService;
    }

    @Override public void requireAvailable() { /* This adapter exists only with the feature installed. */ }

    @Override
    public void validatePortfolio(String localDsl, String candidateDsl) {
        workspaceConflict(() -> portfolioGitService.validatePortfolio(localDsl, candidateDsl));
    }

    @Override
    public void validatePortfolio(String localDsl, String candidateDsl, String username, WorkspaceContext context) {
        workspaceConflict(() -> portfolioGitService.validatePortfolio(localDsl, candidateDsl, username, context), true);
    }

    @Override
    public String commitPortfolio(String branch,
                                  String message,
                                  String username,
                                  WorkspaceContext context) throws IOException {
        return portfolioGitService.commit(branch, message, username, context).commitId();
    }

    @Override
    public void materializePortfolio(String dsl,
                                     String username,
                                     WorkspaceContext context) {
        workspaceConflict(() -> portfolioGitService.materialize(dsl, username, context));
    }

    @Override
    public void materializePortfolioHead(String branch,
                                         String username,
                                         WorkspaceContext context) throws IOException {
        portfolioGitService.materializeHead(branch, username, context);
    }

    private static void workspaceConflict(Runnable action) {
        workspaceConflict(action, false);
    }

    private static void workspaceConflict(Runnable action, boolean incomingPreflight) {
        try { action.run(); }
        catch (PortfolioException failure) {
            // Invalid incoming owner data also requires source reconciliation before any Git publication.
            if (failure.getKind() != PortfolioException.Kind.CONFLICT
                    && !(incomingPreflight && failure.getKind() == PortfolioException.Kind.VALIDATION)) throw failure;
            var conflict = new CommandProblem("PORTFOLIO_RECONCILIATION_REQUIRED", "portfolio", failure.getMessage(), List.of());
            conflict.initCause(failure);
            throw conflict;
        }
    }
}
