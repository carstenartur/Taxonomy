package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PortfolioAnalysisWorkQueueClaimTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private PortfolioAnalysisPersistenceService persistenceService;

    @Autowired
    private PortfolioAnalysisWorkQueue workQueue;

    @Test
    void pendingWorkItemCanBeClaimedOnlyOnceWithoutPrematureJobCompletion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        WorkspaceContext context = new WorkspaceContext(
                "claim-" + suffix.toLowerCase(), "ws-claim-" + suffix, "draft");
        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-" + suffix,
                        "Atomic claim project",
                        null,
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                context.username(),
                context);
        var requirement = projectService.createRequirement(
                project.id(),
                new CreateRequirementRequest(
                        "REQ-001",
                        "Atomic claim requirement",
                        "The requirement must be processed by exactly one worker.",
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
        var job = persistenceService.createOrReuseJob(
                project.id(),
                List.of(requirement.id()),
                "MOCK",
                25,
                "claim-" + suffix,
                context.username(),
                context);

        var firstClaim = workQueue.pending(job.id(), project.id());
        var prematureCompletion = persistenceService.completeJob(job.id(), project.id());
        var secondClaim = workQueue.pending(job.id(), project.id());

        assertThat(firstClaim).singleElement()
                .satisfies(item -> assertThat(item.requirementId()).isEqualTo(requirement.id()));
        assertThat(secondClaim).isEmpty();
        assertThat(prematureCompletion.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(prematureCompletion.completedAt()).isNull();
        assertThat(persistenceService.getJob(
                job.id(), project.id(), context.username(), context).items())
                .singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(AnalysisStatus.RUNNING));
    }
}
