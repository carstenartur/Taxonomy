package com.taxonomy.composition.demo;

import com.taxonomy.analysis.demo.DemoScenarioCatalog;
import com.taxonomy.analysis.service.LlmProviderConfig;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Durable authority regressions; no analysis worker or external provider is executed. */
@SpringBootTest(properties = "llm.mock=true")
class ProductDemoAuthorityRegressionTest {
    private static final String USER = "architect";
    @Autowired ProductDemoService demos;
    @Autowired ProductDemoRunRepository runs;
    @Autowired DemoScenarioCatalog scenarios;
    @Autowired ProjectPortfolioService projects;
    @Autowired PortfolioAnalysisPersistenceService analyses;
    @Autowired RequirementAnalysisJobRepository jobs;
    @Autowired RequirementAnalysisJobItemRepository items;
    @Autowired WorkspaceManager workspaces;
    @Autowired LlmProviderConfig providers;

    @ParameterizedTest(name = "{0} rejects changed stored {1}")
    @CsvSource({"get,version", "list,version", "compare,version", "get,fingerprint", "list,fingerprint", "compare,fingerprint"})
    void readsRejectAReplacedScenarioInsteadOfApplyingTheCurrentReference(String operation, String changed) {
        WorkspaceContext context = workspace();
        var run = demos.start(null, USER, context);
        var stored = runs.findById(run.id()).orElseThrow();
        // Models a durable run restored after a same-ID scenario upgrade; the current catalogue stays immutable.
        runs.saveAndFlush(new ProductDemoRun(stored.getId(), stored.getScopeKey(), stored.getProjectId(),
                stored.getRequirementId(), stored.getScenarioId(),
                changed.equals("version") ? stored.getScenarioVersion() + 1 : stored.getScenarioVersion(),
                changed.equals("fingerprint") ? "0".repeat(64) : stored.getScenarioFingerprint(),
                stored.getOwnerUsername(), stored.getCreatedAt()));

        assertThatThrownBy(() -> {
            switch (operation) {
                case "get" -> demos.get(run.id(), USER, context);
                case "list" -> demos.list(run.projectId(), USER, context);
                case "compare" -> demos.compare(run.id(), USER, context);
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(PortfolioException.class)
                .extracting(failure -> ((PortfolioException) failure).getCode())
                .isEqualTo(ProductDemoProblems.SCENARIO_CHANGED);
        assertThat(projects.listRequirementVersions(run.projectId(), run.requirementId(), USER, context)).hasSize(1);
        assertThat(analyses.listJobs(run.projectId(), USER, context)).isEmpty();
    }

    @Test
    void matchingClosedRunRemainsReadableAndComparable() {
        WorkspaceContext context = workspace();
        var run = demos.start(null, USER, context);
        demos.close(run.id(), USER, context);
        assertThat(demos.get(run.id(), USER, context).scenarioFingerprint()).isEqualTo(scenarios.defaultScenario().fingerprint());
        assertThat(demos.list(run.projectId(), USER, context)).singleElement().satisfies(view -> assertThat(view.closedAt()).isNotNull());
        assertThat(demos.compare(run.id(), USER, context).report().overall())
                .isEqualTo(com.taxonomy.analysis.demo.evaluation.DemoReferenceEvaluator.Overall.INCOMPLETE);
    }

    @Test
    void markerlessPortableReplayRetryIsRejectedWithoutChangingTheFailedJob() {
        WorkspaceContext context = workspace();
        var project = projects.createProject(new CreateProjectRequest("RESTORED-" + UUID.randomUUID(),
                "Restored portable job", null, ProjectStatus.ACTIVE, null, null, null, null), USER, context);
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("R-1", "Requirement",
                "Retained requirement text", RequirementStatus.DRAFT, null, null, null, null, null, null, null), USER, context);
        // Portable job data retains provider identity, but the durable demo marker was not included in the archive.
        // Materialize genuine owner entities, without calling admission or fabricating a worker result.
        var retainedRequirement = projects.requireRequirement(project.id(), requirement.id(), USER, context);
        var portable = jobs.saveAndFlush(new RequirementAnalysisJob(UUID.randomUUID().toString(),
                projects.requireProject(project.id(), USER, context), UUID.randomUUID().toString(),
                "DEMO.TIME-TRACKING-V1.R0", 25, USER, context.workspaceId(), 1, java.time.Instant.now()));
        var retainedItem = new RequirementAnalysisJobItem(portable, retainedRequirement, projects.currentVersion(retainedRequirement));
        retainedItem.fail("retained portable failure", java.time.Instant.now());
        items.saveAndFlush(retainedItem);
        var job = analyses.completeJob(portable.getId(), project.id(), PortfolioScope.key(USER, context));
        assertThat(runs.findByProjectIdAndScopeKey(project.id(), PortfolioScope.key(USER, context))).isEmpty();
        assertThat(providers.isScenarioReplay(portable.getProvider())).isTrue();
        var before = analyses.getJob(job.id(), project.id(), USER, context);

        assertThatThrownBy(() -> analyses.prepareFailedItemsForRetry(job.id(), project.id(), USER, context))
                .isInstanceOf(PortfolioException.class)
                .extracting(failure -> ((PortfolioException) failure).getCode())
                .isEqualTo("PROVIDER_REQUIRES_PROJECT_BINDING");

        assertThat(analyses.getJob(job.id(), project.id(), USER, context)).isEqualTo(before);
        assertThat(analyses.listRequirementSnapshots(project.id(), requirement.id(), USER, context)).isEmpty();
    }

    @Test
    void anOrdinaryFailedJobCanStillRetryWithoutADemoMarker() {
        WorkspaceContext context = workspace();
        var project = projects.createProject(new CreateProjectRequest("NORMAL-" + UUID.randomUUID(),
                "Normal retry", null, ProjectStatus.ACTIVE, null, null, null, null), USER, context);
        var requirement = projects.createRequirement(project.id(), new CreateRequirementRequest("R-1", "Requirement",
                "Normal requirement", RequirementStatus.DRAFT, null, null, null, null, null, null, null), USER, context);
        var job = failedJob(project.id(), requirement.id(), "MOCK", context);
        var retry = analyses.prepareFailedItemsForRetry(job.id(), project.id(), USER, context);
        assertThat(retry.provider()).isEqualTo("MOCK");
        assertThat(retry.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(retry.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(item.attempt()).isEqualTo(2);
        });
    }

    @Test
    void anExactlyBoundFailedReplayJobCanRetry() {
        WorkspaceContext context = workspace();
        var run = demos.start(null, USER, context);
        var job = failedJob(run.projectId(), run.requirementId(), null, context);
        var retry = analyses.prepareFailedItemsForRetry(job.id(), run.projectId(), USER, context);
        assertThat(retry.provider()).isEqualTo(run.activeProvider());
        assertThat(retry.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(retry.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(item.attempt()).isEqualTo(2);
        });
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView failedJob(Long projectId, Long requirementId,
                                                                             String provider, WorkspaceContext context) {
        var job = analyses.createOrReuseJob(projectId, List.of(requirementId), provider, 25, UUID.randomUUID().toString(), USER, context);
        analyses.failItem(job.items().getFirst().id(), job.id(), projectId, PortfolioScope.key(USER, context), new IllegalStateException("retained failure"));
        return analyses.completeJob(job.id(), projectId, PortfolioScope.key(USER, context));
    }

    private WorkspaceContext workspace() {
        var workspace = workspaces.createWorkspace(USER, "Authority " + UUID.randomUUID(), "Product demo authority");
        workspace = workspaces.provisionWorkspaceRepository(USER, workspace.getWorkspaceId());
        return new WorkspaceContext(USER, workspace.getWorkspaceId(), workspace.getCurrentBranch(), workspace.getSourceRepositoryId());
    }
}
