package com.taxonomy.composition.demo;

import com.taxonomy.analysis.demo.DemoCatalogueValidator;
import com.taxonomy.analysis.demo.DemoProviderIds;
import com.taxonomy.analysis.demo.DemoScenario;
import com.taxonomy.analysis.demo.DemoScenarioCatalog;
import com.taxonomy.analysis.demo.evaluation.DemoReference;
import com.taxonomy.analysis.demo.evaluation.DemoReferenceEvaluator;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Starts, reads, compares and closes isolated product demos (issue #1184).
 *
 * <p>Each start creates a fresh, clearly labelled demo project with exactly the
 * scenario requirement R0; existing drafts, projects and requirements are never
 * touched. Analyses, reformulation, explicit adoption and exports then run on
 * the normal product paths; only the external AI reply is replaced by the
 * strict scenario replay derived from this run.</p>
 */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio", "analysis"})
@Service
public class ProductDemoService {

    public record DemoRunView(String id, Long projectId, Long requirementId, String scenarioId, int scenarioVersion,
                              String scenarioFingerprint, String catalogueSource, Map<String, String> title,
                              Map<String, String> banner, String requirementKey, String requirementText,
                              List<DemoScenario.Station> stations, List<DemoScenario.DemoDecision> demoDecisions,
                              List<DemoScenario.DemoReview> demoReviews, String activeProvider,
                              int currentRequirementVersion, Instant createdAt, Instant closedAt) { }

    public record DemoComparison(String runId, String a0SnapshotId, String a1SnapshotId,
                                 DemoReferenceEvaluator.Report report) { }

    private final ProductDemoRunRepository runs;
    private final ProjectPortfolioService projects;
    private final PortfolioAnalysisPersistenceService analyses;
    private final DemoScenarioCatalog catalog;
    private final TaxonomyService taxonomy;

    public ProductDemoService(ProductDemoRunRepository runs, ProjectPortfolioService projects,
                              PortfolioAnalysisPersistenceService analyses,
                              DemoScenarioCatalog catalog, TaxonomyService taxonomy) {
        this.runs = runs;
        this.projects = projects;
        this.analyses = analyses;
        this.catalog = catalog;
        this.taxonomy = taxonomy;
    }

    public DemoScenarioCatalog catalog() {
        return catalog;
    }

    @Transactional
    public DemoRunView start(String scenarioId, String username, WorkspaceContext context) {
        DemoScenario scenario = scenarioId == null || scenarioId.isBlank()
                ? catalog().defaultScenario()
                : catalog().find(scenarioId.strip()).orElseThrow(() -> ProductDemoProblems.notFound(
                        ProductDemoProblems.SCENARIO_NOT_FOUND, "Unknown demo scenario: " + scenarioId));
        verifyCatalogue(scenario);
        String runId = UUID.randomUUID().toString();
        // Demo data is authored content: it uses the scenario's own language, never server-side literals.
        String language = scenario.requirement().language();
        String banner = scenario.banner().get(language);
        String revision = scenario.id() + " v" + scenario.version();
        var project = projects.createProject(new CreateProjectRequest(
                "DEMO-" + scenario.id().toUpperCase(Locale.ROOT) + "-" + runId.substring(0, 8).toUpperCase(Locale.ROOT),
                "DEMO · " + scenario.title().get(language), banner + " · " + revision,
                ProjectStatus.PLANNING, null, null, null, null), username, context);
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest(
                scenario.requirement().key(), scenario.requirement().title(), scenario.r0Text(),
                RequirementStatus.DRAFT, null, null, RequirementType.FUNCTIONAL, null, null,
                banner + " · " + revision + " · R0", null),
                username, context);
        var run = runs.save(new ProductDemoRun(runId, PortfolioScope.key(username, context), project.id(),
                requirement.id(), scenario.id(), scenario.version(), scenario.fingerprint(),
                PortfolioScope.username(username, context), Instant.now()));
        return view(run, scenario, 1, requirement.requirementKey());
    }

    @Transactional(readOnly = true)
    public DemoRunView get(String runId, String username, WorkspaceContext context) {
        return view(require(runId, username, context), username, context);
    }

    private DemoRunView view(ProductDemoRun run, String username, WorkspaceContext context) {
        var requirement = projects.requireRequirement(run.getProjectId(), run.getRequirementId(), username, context);
        int version = projects.currentVersion(requirement).getVersionNumber();
        return view(run, scenarioOf(run), version, requirement.getRequirementKey());
    }

    /** The caller's demo runs, optionally restricted to one project (at most one run per project). */
    @Transactional(readOnly = true)
    public List<DemoRunView> list(Long projectId, String username, WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        List<ProductDemoRun> selected = projectId == null ? runs.findByScopeKeyOrderByCreatedAtDesc(scopeKey)
                : runs.findByProjectIdAndScopeKey(projectId, scopeKey).map(List::of).orElse(List.of());
        return selected.stream().map(run -> view(run, username, context)).toList();
    }

    /** Closing takes the same row lock as provider admission, so no replay analysis is admitted afterwards. */
    @Transactional
    public DemoRunView close(String runId, String username, WorkspaceContext context) {
        ProductDemoRun run = runs.lockByIdAndScopeKey(requireRunId(runId), PortfolioScope.key(username, context))
                .orElseThrow(() -> runNotFound(runId));
        run.close(Instant.now());
        return view(run, username, context);
    }

    /**
     * Independent reference evaluation of the latest successful A0 and A1 analyses.
     * Missing analyses are reported as NOT_EXECUTED, never as negative findings.
     */
    @Transactional(readOnly = true)
    public DemoComparison compare(String runId, String username, WorkspaceContext context) {
        ProductDemoRun run = require(runId, username, context);
        List<SnapshotSummary> snapshots = analyses.listRequirementSnapshots(
                run.getProjectId(), run.getRequirementId(), username, context);
        Map<Integer, String> texts = new java.util.HashMap<>();
        for (RequirementVersionView version : projects.listRequirementVersions(
                run.getProjectId(), run.getRequirementId(), username, context))
            texts.put(version.versionNumber(), version.text());
        var a0 = latest(snapshots, run, 0);
        var a1 = latest(snapshots, run, 1);
        var report = DemoReferenceEvaluator.evaluate(DemoReference.load(run.getScenarioId()),
                observation(a0, texts, run, username, context), observation(a1, texts, run, username, context));
        return new DemoComparison(run.getId(), a0.map(SnapshotSummary::id).orElse(null),
                a1.map(SnapshotSummary::id).orElse(null), report);
    }

    private Optional<SnapshotSummary> latest(List<SnapshotSummary> snapshots, ProductDemoRun run, int iteration) {
        String provider = DemoProviderIds.providerId(run.getScenarioId(), iteration).value();
        return snapshots.stream()
                .filter(s -> s.requirementVersionNumber() == iteration + 1)
                .filter(s -> provider.equalsIgnoreCase(s.provider()))
                // An incomplete (PARTIAL) analysis is never evaluated as if it had completed.
                .filter(s -> s.status() == AnalysisStatus.SUCCESS)
                .max(Comparator.comparing(SnapshotSummary::createdAt));
    }

    private DemoReferenceEvaluator.Observation observation(Optional<SnapshotSummary> summary, Map<Integer, String> texts,
                                                           ProductDemoRun run, String username,
                                                           WorkspaceContext context) {
        if (summary.isEmpty()) return new DemoReferenceEvaluator.Observation(null, null, Set.of());
        SnapshotDetail detail = analyses.getSnapshot(run.getProjectId(), summary.get().id(), username, context);
        Map<String, Integer> scores = detail.analysis() != null ? detail.analysis().getRawScores() : null;
        Set<String> relations = new LinkedHashSet<>();
        if (detail.relationMappings() != null)
            detail.relationMappings().forEach(r -> relations.add(r.sourceCode() + " " + r.relationType() + " " + r.targetCode()));
        return new DemoReferenceEvaluator.Observation(texts.get(summary.get().requirementVersionNumber()), scores, relations);
    }

    private ProductDemoRun require(String runId, String username, WorkspaceContext context) {
        return runs.findByIdAndScopeKey(requireRunId(runId), PortfolioScope.key(username, context))
                .orElseThrow(() -> runNotFound(runId));
    }

    private static String requireRunId(String runId) {
        if (runId == null || runId.isBlank()) throw PortfolioException.validation("demo run id is required");
        return runId.strip();
    }

    private static PortfolioException runNotFound(String runId) {
        return ProductDemoProblems.notFound(ProductDemoProblems.RUN_NOT_FOUND, "Product demo not found: " + runId);
    }

    private DemoScenario scenarioOf(ProductDemoRun run) {
        return catalog().find(run.getScenarioId()).orElseThrow(() -> ProductDemoProblems.conflict(
                ProductDemoProblems.SCENARIO_CHANGED, "Scenario " + run.getScenarioId() + " is no longer available"));
    }

    private DemoRunView view(ProductDemoRun run, DemoScenario scenario, int currentVersion, String requirementKey) {
        int iteration = currentVersion - 1;
        String provider = iteration >= 0 && iteration <= scenario.maxIteration()
                ? DemoProviderIds.providerId(run.getScenarioId(), iteration).value() : null;
        return new DemoRunView(run.getId(), run.getProjectId(), run.getRequirementId(), run.getScenarioId(),
                run.getScenarioVersion(), run.getScenarioFingerprint(), scenario.catalogueSource(), scenario.title(),
                scenario.banner(), requirementKey, scenario.r0Text(), scenario.stations(), scenario.demoDecisions(),
                scenario.demoReviews(), provider, currentVersion, run.getCreatedAt(), run.getClosedAt());
    }

    /** The scenario is validated against the live catalogue before any demo data is created. */
    private void verifyCatalogue(DemoScenario scenario) {
        TaxonomyService service = taxonomy;
        try {
            DemoCatalogueValidator.validate(scenario, new DemoCatalogueValidator.CatalogueView() {
                @Override public Optional<String> title(String code) {
                    return Optional.ofNullable(service.getNodeByCode(code)).map(TaxonomyNode::getNameEn);
                }
                @Override public List<String> children(String code) {
                    return service.getChildrenOf(code).stream().map(TaxonomyNode::getCode).toList();
                }
            });
        } catch (IllegalStateException mismatch) {
            throw ProductDemoProblems.conflict(ProductDemoProblems.CATALOGUE_MISMATCH, mismatch.getMessage());
        }
    }
}
