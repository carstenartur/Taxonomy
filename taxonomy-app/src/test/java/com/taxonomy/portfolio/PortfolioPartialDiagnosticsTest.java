package com.taxonomy.portfolio;

import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Registered in the normal application suite; no model or transport is mocked. */
@SpringBootTest(properties = {"embedding.enabled=false", "embedding.allow-download=false"})
class PortfolioPartialDiagnosticsTest {
    @Autowired ProjectPortfolioService projects;
    @Autowired PortfolioAnalysisPersistenceService persistence;

    @ParameterizedTest
    @ValueSource(strings = {"error", "warning", "bounded", "bounded-warning", "unicode", "boundary", "success", "unknown"})
    void persistsBoundedReasonWithoutLosingFullEvidence(String scenario) {
        PortfolioPartialDiagnosticsChecks.check(projects, persistence, scenario);
    }
}
