package com.taxonomy.composition.demo;

import com.taxonomy.analysis.demo.DemoProviderIds;
import com.taxonomy.analysis.demo.DemoScenario;
import com.taxonomy.analysis.demo.DemoScenarioCatalog;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectProviderBinding;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Binds a product-demo project to its scenario replay.
 *
 * <p>The provider of a demo project is derived exclusively from its durable
 * {@link ProductDemoRun} and the analysed requirement version: version 1 is
 * replayed as R0, version 2 (the explicitly adopted reformulation) as R1. Any
 * other requested provider is rejected, so a demo project can never reach a live
 * model. The run row stays write-locked until the surrounding transaction ends,
 * so closing a run serializes with admission.</p>
 */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio", "analysis"})
@Component
public class ProductDemoProviderBinding implements ProjectProviderBinding {
    private final ProductDemoRunRepository runs;
    private final DemoScenarioCatalog scenarios;
    private final ProjectPortfolioService projects;

    public ProductDemoProviderBinding(ProductDemoRunRepository runs, DemoScenarioCatalog scenarios,
                                      ProjectPortfolioService projects) {
        this.runs = runs;
        this.scenarios = scenarios;
        this.projects = projects;
    }

    @Override
    @Transactional
    public Optional<String> admittedProvider(Long projectId, List<Target> targets, String requestedProvider,
                                             String username, WorkspaceContext context) {
        if (projectId == null) return Optional.empty();
        var run = runs.lockByProjectIdAndScopeKey(projectId, PortfolioScope.key(username, context));
        if (run.isEmpty()) return Optional.empty();
        Target target = singleTarget(run.get(), targets);
        int versionNumber = projects.listRequirementVersions(projectId, target.requirementId(), username, context).stream()
                .filter(version -> target.requirementVersionId().equals(version.id()))
                .mapToInt(RequirementVersionView::versionNumber).findFirst()
                .orElseThrow(() -> ProductDemoProblems.notFound(ProductDemoProblems.REQUIREMENT_MISMATCH,
                        "Requirement version not found: " + target.requirementVersionId()));
        return Optional.of(admit(run.get(), scenarios.find(run.get().getScenarioId()).orElse(null),
                versionNumber, requestedProvider));
    }

    /** Pure admission rule; failing visibly instead of falling back to any other provider. */
    static String admit(ProductDemoRun run, DemoScenario scenario, int requirementVersionNumber, String requestedProvider) {
        Objects.requireNonNull(run, "run");
        if (run.isClosed())
            throw ProductDemoProblems.conflict(ProductDemoProblems.RUN_CLOSED, "Product demo " + run.getId() + " is closed");
        if (scenario == null || scenario.version() != run.getScenarioVersion()
                || !scenario.fingerprint().equals(run.getScenarioFingerprint()))
            throw ProductDemoProblems.conflict(ProductDemoProblems.SCENARIO_CHANGED, "Scenario " + run.getScenarioId()
                    + " no longer matches the recorded version of this demo run");
        int iteration = requirementVersionNumber - 1;
        if (iteration < 0 || iteration > scenario.maxIteration())
            throw ProductDemoProblems.validation(ProductDemoProblems.ITERATION_UNAVAILABLE,
                    "The demo scenario has no replies for requirement version " + requirementVersionNumber);
        String admitted = DemoProviderIds.providerId(run.getScenarioId(), iteration).value();
        if (requestedProvider != null && !requestedProvider.isBlank()
                && !admitted.equalsIgnoreCase(requestedProvider.strip()))
            throw ProductDemoProblems.validation(ProductDemoProblems.PROVIDER_REJECTED, "A product demo never uses provider "
                    + requestedProvider.strip() + "; its replies come only from the scenario replay " + admitted);
        return admitted;
    }

    private static Target singleTarget(ProductDemoRun run, List<Target> targets) {
        if (targets == null || targets.size() != 1 || !run.getRequirementId().equals(targets.getFirst().requirementId()))
            throw ProductDemoProblems.validation(ProductDemoProblems.REQUIREMENT_MISMATCH,
                    "A product demo analyses exactly its demo requirement");
        return targets.getFirst();
    }
}
