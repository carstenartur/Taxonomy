package com.taxonomy.portfolio.demo;

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
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<DemoScenarioCatalog> catalogs;
    private final ObjectProvider<TaxonomyService> taxonomy;

    public ProductDemoService(ProductDemoRunRepository runs, ProjectPortfolioService projects,
                              PortfolioAnalysisPersistenceService analyses,
                              ObjectProvider<DemoScenarioCatalog> catalogs,
                              ObjectProvider<TaxonomyService> taxonomy) {
        this.runs = runs;
        this.projects = projects;
        this.analyses = analyses;
        this.catalogs = catalogs;
        this.taxonomy = taxonomy;
    }

    public DemoScenarioCatalog catalog() {
        return catalogs.getIfAvailable(DemoScenarioCatalog::builtin);
    }

    @Transactional
    public DemoRunView start(String scenarioId, String username, WorkspaceContext context) {
        DemoScenario scenario = scenarioId == null || scenarioId.isBlank()
                ? catalog().defaultScenario()
                : catalog().find(scenarioId.strip()).orElseThrow(() ->
                        PortfolioException.notFound("Unknown demo scenario: " + scenarioId));
        verifyCatalogue(scenario);
        String runId = UUID.randomUUID().toString();
        String label = scenario.title().getOrDefault("de", scenario.id());
        var project = projects.createProject(new CreateProjectRequest(
                "DEMO-" + scenario.id().toUpperCase(Locale.ROOT) + "-" + runId.substring(0, 8).toUpperCase(Locale.ROOT),
                "Produktdemo · " + label,
                scenario.banner().getOrDefault("de", "DEMODUS") + " · Szenario " + scenario.id() + " v" + scenario.version(),
                ProjectStatus.PLANNING, null, null, null, null), username, context);
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest(
                scenario.requirement().key(), scenario.requirement().title(), scenario.r0Text(),
                RequirementStatus.DRAFT, null, null, RequirementType.FUNCTIONAL, null, null,
                "Produktdemo " + scenario.id() + " v" + scenario.version() + ": Ausgangsanforderung R0", null),
                username, context);
        var run = runs.save(new ProductDemoRun(runId, PortfolioScope.key(username, context), project.id(),
                requirement.id(), scenario.id(), scenario.version(), scenario.fingerprint(),
                PortfolioScope.username(username, context), Instant.now()));
        return view(run, scenario, 1, requirement.requirementKey());
    }

    @Transactional(readOnly = true)
    public DemoRunView get(String runId, String username, WorkspaceContext context) {
        ProductDemoRun run = require(runId, username, context);
        var requirement = projects.requireRequirement(run.getProjectId(), run.getRequirementId(), username, context);
        int version = projects.currentVersion(requirement).getVersionNumber();
        return view(run, scenarioOf(run), version, requirement.getRequirementKey());
    }

    @Transactional(readOnly = true)
    public List<DemoRunView> list(String username, WorkspaceContext context) {
        return runs.findByScopeKeyOrderByCreatedAtDesc(PortfolioScope.key(username, context)).stream()
                .map(run -> get(run.getId(), username, context)).toList();
    }

    @Transactional
    public DemoRunView close(String runId, String username, WorkspaceContext context) {
        ProductDemoRun run = require(runId, username, context);
        run.close(Instant.now());
        return get(runId, username, context);
    }

    /** Demo marker of a project in the given tenant scope, if any. */
    @Transactional(readOnly = true)
    public Optional<ProductDemoRun> findForProject(Long projectId, String scopeKey) {
        return projectId == null ? Optional.empty() : runs.findByProjectIdAndScopeKey(projectId, scopeKey);
    }

    /** Scenario replay provider for analysing or reformulating the given requirement version of a demo project. */
    @Transactional(readOnly = true)
    public Optional<String> demoProviderFor(Long projectId, String scopeKey, Long requirementId,
                                            int requirementVersionNumber, String requestedProvider) {
        var run = findForProject(projectId, scopeKey);
        if (run.isEmpty()) {
            ProductDemoAdmission.rejectDemoProviderForNormalProject(requestedProvider);
            return Optional.empty();
        }
        return Optional.of(ProductDemoAdmission.admittedProvider(run.get(), catalog().find(run.get().getScenarioId())
                .orElse(null), List.of(requirementId), requirementVersionNumber, requestedProvider));
    }

    /** Replay provider for reformulating a demo requirement from the given source version; empty for normal projects. */
    @Transactional(readOnly = true)
    public Optional<String> reformulationProviderFor(Long projectId, Long requirementId, long sourceVersionId,
                                                     String username, WorkspaceContext context) {
        var run = findForProject(projectId, PortfolioScope.key(username, context));
        if (run.isEmpty()) return Optional.empty();
        int versionNumber = projects.listRequirementVersions(projectId, requirementId, username, context).stream()
                .filter(v -> v.id() != null && v.id() == sourceVersionId).mapToInt(RequirementVersionView::versionNumber)
                .findFirst().orElseThrow(() -> PortfolioException.notFound("Requirement version not found: " + sourceVersionId));
        return Optional.of(ProductDemoAdmission.admittedProvider(run.get(), catalog().find(run.get().getScenarioId())
                .orElse(null), List.of(requirementId), versionNumber, null));
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
                .filter(s -> s.status() == AnalysisStatus.SUCCESS || s.status() == AnalysisStatus.PARTIAL)
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
        if (runId == null || runId.isBlank()) throw PortfolioException.validation("demo run id is required");
        return runs.findByIdAndScopeKey(runId.strip(), PortfolioScope.key(username, context))
                .orElseThrow(() -> PortfolioException.notFound("Product demo not found: " + runId));
    }

    private DemoScenario scenarioOf(ProductDemoRun run) {
        return catalog().find(run.getScenarioId()).orElseThrow(() ->
                PortfolioException.conflict("DEMO_SCENARIO_CHANGED: scenario " + run.getScenarioId() + " is no longer available"));
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
        TaxonomyService service = taxonomy.getIfAvailable();
        if (service == null) throw PortfolioException.unavailable("Catalogue unavailable; product demo cannot start", null);
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
            throw PortfolioException.conflict(mismatch.getMessage());
        }
    }
}
