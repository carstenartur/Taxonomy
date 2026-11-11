package com.taxonomy.portfolio;

import org.junit.jupiter.api.Tag;

import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Registered in the normal application suite; no model or transport is mocked. */
@SpringBootTest(properties = {"embedding.enabled=false", "embedding.allow-download=false"})
@Tag("ci-app-shard-domain-integration")
class PortfolioPartialDiagnosticsTest extends RetainedPortfolioCatalogue {
    @Autowired ProjectPortfolioService projects;
    @Autowired PortfolioAnalysisPersistenceService persistence;
    @Autowired WorkspaceResolver workspaceResolver;

    @ParameterizedTest
    @ValueSource(strings = {"error", "warning", "bounded", "bounded-warning", "unicode", "boundary", "success", "unknown"})
    void persistsBoundedReasonWithoutLosingFullEvidence(String scenario) {
        PortfolioPartialDiagnosticsChecks.check(projects, persistence, workspaceResolver, scenario);
    }
}
