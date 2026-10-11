package com.taxonomy.portfolio.demo;

import com.taxonomy.analysis.demo.DemoProviderIds;
import com.taxonomy.analysis.demo.DemoScenario;
import com.taxonomy.portfolio.service.PortfolioException;

import java.util.List;
import java.util.Objects;

/**
 * Server-side admission rule for demo analyses.
 *
 * <p>The provider of a demo project is derived exclusively from its durable
 * {@link ProductDemoRun} and the analysed requirement version: version 1 is
 * replayed as R0, version 2 (the explicitly adopted reformulation) as R1. A demo
 * provider requested for a normal project and any live provider requested for a
 * demo project are rejected, so neither side can leak into the other.</p>
 */
public final class ProductDemoAdmission {

    private ProductDemoAdmission() { }

    /** Provider admitted for a demo run, failing visibly instead of falling back to a live model. */
    public static String admittedProvider(ProductDemoRun run, DemoScenario scenario, List<Long> requirementIds,
                                          int requirementVersionNumber, String requestedProvider) {
        Objects.requireNonNull(run, "run");
        if (run.isClosed()) throw PortfolioException.conflict("DEMO_RUN_CLOSED: product demo " + run.getId() + " is closed");
        if (scenario == null || scenario.version() != run.getScenarioVersion()
                || !scenario.fingerprint().equals(run.getScenarioFingerprint()))
            throw PortfolioException.conflict("DEMO_SCENARIO_CHANGED: scenario " + run.getScenarioId()
                    + " no longer matches the recorded version of this demo run");
        if (requirementIds == null || requirementIds.stream().filter(Objects::nonNull).distinct().count() != 1
                || !run.getRequirementId().equals(requirementIds.stream().filter(Objects::nonNull).findFirst().orElse(null)))
            throw PortfolioException.validation("A product demo analyses exactly its demo requirement");
        int iteration = requirementVersionNumber - 1;
        if (iteration < 0 || iteration > scenario.maxIteration())
            throw PortfolioException.validation("DEMO_ITERATION_UNAVAILABLE: the demo scenario has no replies for requirement version "
                    + requirementVersionNumber);
        String admitted = DemoProviderIds.providerId(run.getScenarioId(), iteration).value();
        if (requestedProvider != null && !requestedProvider.isBlank()
                && !admitted.equalsIgnoreCase(requestedProvider.strip()))
            throw PortfolioException.validation("A product demo never uses provider " + requestedProvider.strip()
                    + "; its replies come only from the scenario replay " + admitted);
        return admitted;
    }

    /** Normal projects never receive scenario replies. */
    public static void rejectDemoProviderForNormalProject(String requestedProvider) {
        if (DemoProviderIds.isDemo(requestedProvider))
            throw PortfolioException.validation("Scenario-replay providers are available only inside a product demo");
    }
}
