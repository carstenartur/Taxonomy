package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.model.RepositoryVisibility;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end evidence that asynchronous analysis identities cannot cross tenants. */
@SpringBootTest
class AnalysisArtifactTenantIsolationTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private PortfolioAnalysisPersistenceService persistenceService;

    @Autowired
    private PortfolioAnalysisWorkQueue workQueue;

    @Autowired
    private RequirementAnalysisJobRepository jobRepository;

    @Autowired
    private RequirementAnalysisJobItemRepository itemRepository;

    @Autowired
    private SystemRepositoryService systemRepositoryService;

    @Test
    void identicalAnalysisRequestsRemainRepositoryAndBranchLocal() {
        String repositoryA = repository("Analysis tenant A");
        String repositoryB = repository("Analysis tenant B");
        WorkspaceContext aMain = context(repositoryA, "main");
        WorkspaceContext aDraft = context(repositoryA, "draft");
        WorkspaceContext bMain = context(repositoryB, "main");

        var projectAMain = createProject(aMain);
        var projectADraft = createProject(aDraft);
        var projectBMain = createProject(bMain);
        var requirementAMain = createRequirement(projectAMain.id(), aMain);
        var requirementADraft = createRequirement(projectADraft.id(), aDraft);
        var requirementBMain = createRequirement(projectBMain.id(), bMain);

        var jobAMain = createJob(projectAMain.id(), requirementAMain.id(), aMain);
        var jobADraft = createJob(projectADraft.id(), requirementADraft.id(), aDraft);
        var jobBMain = createJob(projectBMain.id(), requirementBMain.id(), bMain);

        assertThat(jobAMain.id())
                .isNotEqualTo(jobADraft.id())
                .isNotEqualTo(jobBMain.id());

        String scopeAMain = PortfolioScope.key("architect", aMain);
        String scopeADraft = PortfolioScope.key("architect", aDraft);
        String scopeBMain = PortfolioScope.key("architect", bMain);

        assertThat(jobRepository.findByIdAndProjectIdAndScopeKey(
                jobAMain.id(), projectAMain.id(), scopeAMain)).isPresent();
        assertThat(jobRepository.findByIdAndProjectIdAndScopeKey(
                jobAMain.id(), projectAMain.id(), scopeADraft)).isEmpty();
        assertThat(jobRepository.findByIdAndProjectIdAndScopeKey(
                jobAMain.id(), projectAMain.id(), scopeBMain)).isEmpty();

        assertThat(itemRepository
                .findByJobIdAndProjectIdAndScopeKeyOrderByRequirementRequirementKeyAsc(
                        jobAMain.id(), projectAMain.id(), scopeAMain))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getProjectId()).isEqualTo(projectAMain.id());
                    assertThat(item.getScopeKey()).isEqualTo(scopeAMain);
                    assertThat(item.getRequirementId()).isEqualTo(requirementAMain.id());
                });
        assertThat(itemRepository
                .findByJobIdAndProjectIdAndScopeKeyOrderByRequirementRequirementKeyAsc(
                        jobAMain.id(), projectAMain.id(), scopeADraft)).isEmpty();

        assertThatThrownBy(() -> workQueue.pending(
                jobAMain.id(), projectAMain.id(), scopeADraft))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Analysis job not found");

        var claimed = workQueue.pending(jobAMain.id(), projectAMain.id(), scopeAMain);
        assertThat(claimed).singleElement().satisfies(item -> {
            assertThat(item.jobId()).isEqualTo(jobAMain.id());
            assertThat(item.projectId()).isEqualTo(projectAMain.id());
            assertThat(item.scopeKey()).isEqualTo(scopeAMain);
        });

        Long itemId = claimed.getFirst().itemId();
        assertThatThrownBy(() -> persistenceService.failItem(
                itemId,
                jobAMain.id(),
                projectAMain.id(),
                scopeADraft,
                new IllegalStateException("foreign worker")))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("Analysis job item not found");
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView createJob(
            Long projectId,
            Long requirementId,
            WorkspaceContext context) {
        return persistenceService.createOrReuseJob(
                projectId,
                List.of(requirementId),
                "MOCK",
                25,
                "same-idempotency-key",
                "architect",
                context);
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView createProject(
            WorkspaceContext context) {
        return projectService.createProject(
                new CreateProjectRequest(
                        "SAME-PROJECT",
                        "Exact analysis tenant project",
                        null,
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                "architect",
                context);
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView createRequirement(
            Long projectId,
            WorkspaceContext context) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        "SAME-REQUIREMENT",
                        "Exact analysis tenant requirement",
                        "Shared requirement text",
                        RequirementStatus.APPROVED,
                        50,
                        Criticality.HIGH,
                        RequirementType.FUNCTIONAL,
                        ReviewStatus.CONFIRMED,
                        "architect",
                        "initial version",
                        null),
                "architect",
                context);
    }

    private String repository(String displayName) {
        String discriminator = UUID.randomUUID().toString();
        return systemRepositoryService.createCentralRepository(
                displayName,
                "analysis-tenant-" + discriminator,
                "Analysis isolation fixture",
                RepositoryVisibility.PRIVATE,
                "architect",
                "main").getRepositoryId();
    }

    private static WorkspaceContext context(String repositoryId, String branch) {
        return new WorkspaceContext("architect", null, branch, repositoryId);
    }
}
