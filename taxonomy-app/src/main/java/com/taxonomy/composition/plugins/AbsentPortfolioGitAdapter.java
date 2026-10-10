package com.taxonomy.composition.plugins;

import com.taxonomy.shared.features.ConditionalOnFeature;

import com.taxonomy.versioning.service.VersioningPortfolioGitPort;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspacePortfolioGitPort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Keep core workspace reads available, but never silently omit persisted portfolio content. */
@Component
@ConditionalOnFeature(value = "portfolio", present = false)
final class AbsentPortfolioGitAdapter implements WorkspacePortfolioGitPort, VersioningPortfolioGitPort {
    @Override public void requireAvailable() { throw unavailable(); }
    @Override public String commitPortfolio(String branch, String message, String username, WorkspaceContext context) {
        throw unavailable();
    }
    @Override public void materializePortfolio(String dsl, String username, WorkspaceContext context) {
        throw unavailable();
    }
    @Override public void materializePortfolioHead(String branch, String username, WorkspaceContext context) {
        throw unavailable();
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Portfolio feature is required for this operation");
    }
}
