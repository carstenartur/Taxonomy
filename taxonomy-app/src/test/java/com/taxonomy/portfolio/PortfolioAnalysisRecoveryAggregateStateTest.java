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
import com.taxonomy.portfolio.service.PortfolioAnalysisRecoveryService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression coverage for aggregate status reconciliation around prepared retries. */
@SpringBootTest
class PortfolioAnalysisRecoveryAggregateStateTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private PortfolioAnalysisPersistenceService persistenceService;

    @Autowired
    private PortfolioAnalysisRecoveryService recoveryService;

    @Autowired
    private PortfolioAnalysisWorkQueue workQueue;

    @Test
    void preparedItemsRestorePendingButAnActiveClaimKeepsTheJobRunning() {
        WorkspaceContext context = context();
        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(),
                        "Aggregate recovery project",
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
                        "Prepared retry requirement",
                        "Requirement waiting for a worker claim",
                        RequirementStatus.APPROVED,
                        50,
                        Criticality.HIGH,
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
                "aggregate-recovery-" + UUID.randomUUID(),
                context.username(),
                context);
        String scopeKey = PortfolioScope.key(context.username(), context);

        // Simulate a completing concurrent worker that temporarily promoted the
        // aggregate although this exact item still only represents prepared work.
        persistenceService.markJobRunning(job.id(), project.id(), scopeKey);
        assertThat(persistenceService.getJob(
                job.id(), project.id(), context.username(), context).status())
                .isEqualTo(AnalysisStatus.RUNNING);

        assertThat(recoveryService.markPendingWhenOnlyPreparedItemsRemain(
                job.id(), project.id(), scopeKey))
                .isTrue();
        var pending = persistenceService.getJob(
                job.id(), project.id(), context.username(), context);
        assertThat(pending.status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(pending.items()).singleElement()
                .satisfies(item -> assertThat(item.status())
                        .isEqualTo(AnalysisStatus.PENDING));

        assertThat(workQueue.pending(job.id(), project.id(), scopeKey))
                .hasSize(1);
        persistenceService.markJobRunning(job.id(), project.id(), scopeKey);

        assertThat(recoveryService.markPendingWhenOnlyPreparedItemsRemain(
                job.id(), project.id(), scopeKey))
                .isFalse();
        var running = persistenceService.getJob(
                job.id(), project.id(), context.username(), context);
        assertThat(running.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(running.items()).singleElement()
                .satisfies(item -> assertThat(item.status())
                        .isEqualTo(AnalysisStatus.RUNNING));
    }

    private static WorkspaceContext context() {
        String suffix = shortId().toLowerCase();
        String username = "aggregate-recovery-" + suffix;
        return new WorkspaceContext(username, "ws-" + suffix, "draft");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
