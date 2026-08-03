package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisRecoveryService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "taxonomy.portfolio.max-analysis-batch=2")
class PortfolioAnalysisRecoveryAndLimitsTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private ProjectRequirementAnalysisService analysisService;

    @Autowired
    private PortfolioAnalysisPersistenceService persistenceService;

    @Autowired
    private PortfolioAnalysisWorkQueue workQueue;

    @Autowired
    private PortfolioAnalysisRecoveryService recoveryService;

    @Test
    void rejectsAnalysisBatchAboveConfiguredLimitBeforeCreatingAJob() {
        WorkspaceContext context = context("batch-limit");
        var project = createProject(context);
        createRequirement(project.id(), context, "REQ-001");
        createRequirement(project.id(), context, "REQ-002");
        createRequirement(project.id(), context, "REQ-003");

        assertThatThrownBy(() -> analysisService.analyzeProject(
                project.id(),
                new AnalyzeProjectRequest(List.of(), true, "MOCK", 25, "too-large"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("maximum is 2");
        assertThat(analysisService.listJobs(project.id(), context.username(), context))
                .isEmpty();
    }

    @Test
    void recoversExpiredClaimExactlyOnceAndIncrementsAttemptOnce() {
        WorkspaceContext context = context("claim-recovery");
        var project = createProject(context);
        var requirement = createRequirement(project.id(), context, "REQ-001");
        var job = persistenceService.createOrReuseJob(
                project.id(),
                List.of(requirement.id()),
                "MOCK",
                25,
                "recover-" + UUID.randomUUID(),
                context.username(),
                context);

        assertThat(workQueue.pending(job.id(), project.id())).hasSize(1);
        assertThatThrownBy(() -> recoveryService.prepareRetryableItems(
                job.id(),
                project.id(),
                context.username(),
                context,
                Instant.now().minusSeconds(60)))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("no failed or expired running items");

        int recovered = recoveryService.prepareRetryableItems(
                job.id(),
                project.id(),
                context.username(),
                context,
                Instant.now().plusSeconds(1));

        assertThat(recovered).isEqualTo(1);
        assertThatThrownBy(() -> recoveryService.prepareRetryableItems(
                job.id(),
                project.id(),
                context.username(),
                context,
                Instant.now().plusSeconds(1)))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("no failed or expired running items");
        assertThat(persistenceService.getJob(
                job.id(), project.id(), context.username(), context).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING);
                    assertThat(item.attempt()).isEqualTo(2);
                    assertThat(item.startedAt()).isNull();
                });
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView createProject(
            WorkspaceContext context) {
        return projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(),
                        "Recovery test project",
                        null,
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                context.username(),
                context);
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView createRequirement(
            Long projectId, WorkspaceContext context, String key) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        key,
                        key + " title",
                        "Requirement text for " + key,
                        RequirementStatus.APPROVED,
                        50,
                        Criticality.MEDIUM,
                        RequirementType.FUNCTIONAL,
                        ReviewStatus.CONFIRMED,
                        context.username(),
                        "Initial version",
                        null),
                context.username(),
                context);
    }

    private static WorkspaceContext context(String username) {
        return new WorkspaceContext(
                username,
                "ws-" + username + "-" + shortId(),
                "draft");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
