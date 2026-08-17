package com.taxonomy.portfolio;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisClaimPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisRecoveryService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** End-to-end evidence that a recovered claim invalidates every late worker result. */
@SpringBootTest
class PortfolioAnalysisClaimPersistenceServiceTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private PortfolioAnalysisPersistenceService persistenceService;

    @Autowired
    private PortfolioAnalysisClaimPersistenceService claimPersistenceService;

    @Autowired
    private PortfolioAnalysisWorkQueue workQueue;

    @Autowired
    private PortfolioAnalysisRecoveryService recoveryService;

    @Test
    void expiredWorkerCannotFailOrPersistOverTheNewRetryGeneration() {
        WorkspaceContext context = context();
        var project = createProject(context);
        var requirement = createRequirement(project.id(), context);
        var job = persistenceService.createOrReuseJob(
                project.id(),
                List.of(requirement.id()),
                "MOCK",
                25,
                "claim-generation-" + UUID.randomUUID(),
                context.username(),
                context);
        String scopeKey = PortfolioScope.key(context.username(), context);

        PortfolioAnalysisWorkQueue.WorkItem expiredClaim = workQueue
                .pending(job.id(), project.id(), scopeKey)
                .getFirst();
        persistenceService.markJobRunning(job.id(), project.id(), scopeKey);
        assertThat(expiredClaim.attempt()).isEqualTo(1);
        assertThat(expiredClaim.requirementVersionId())
                .isEqualTo(requirement.currentVersion().id());

        var retryVersion = projectService.addRequirementVersion(
                project.id(),
                requirement.id(),
                new CreateRequirementVersionRequest(
                        "Updated requirement text for the retry generation",
                        "Invalidate the expired worker claim",
                        null),
                context.username(),
                context);

        assertThat(recoveryService.prepareRetryableItems(
                job.id(),
                project.id(),
                context.username(),
                context,
                Instant.now().plusSeconds(1)))
                .isEqualTo(1);

        PortfolioAnalysisWorkQueue.WorkItem activeClaim = workQueue
                .pending(job.id(), project.id(), scopeKey)
                .getFirst();
        persistenceService.markJobRunning(job.id(), project.id(), scopeKey);
        assertThat(activeClaim.attempt()).isEqualTo(2);
        assertThat(activeClaim.requirementVersionId()).isEqualTo(retryVersion.id());

        AnalysisResult analysis = successfulAnalysis();
        String staleSnapshotId = UUID.randomUUID().toString();
        assertThatThrownBy(() -> claimPersistenceService.persistSnapshot(
                expiredClaim,
                staleSnapshotId,
                "portfolio:" + staleSnapshotId,
                analysis,
                null,
                null,
                null,
                "MOCK",
                null,
                "prompt-fingerprint",
                "taxonomy-fingerprint",
                context.username(),
                context,
                1L))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("claim is no longer active");
        assertThatThrownBy(() -> claimPersistenceService.failItem(
                expiredClaim,
                new IllegalStateException("late worker failure")))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("claim is no longer active");

        assertThat(persistenceService.listRequirementSnapshots(
                project.id(), requirement.id(), context.username(), context))
                .isEmpty();
        assertThat(persistenceService.getJob(
                job.id(), project.id(), context.username(), context).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(AnalysisStatus.RUNNING);
                    assertThat(item.attempt()).isEqualTo(2);
                    assertThat(item.requirementVersionId()).isEqualTo(retryVersion.id());
                    assertThat(item.snapshotId()).isNull();
                    assertThat(item.errorMessage()).isNull();
                });

        String currentSnapshotId = UUID.randomUUID().toString();
        var accepted = claimPersistenceService.persistSnapshot(
                activeClaim,
                currentSnapshotId,
                "portfolio:" + currentSnapshotId,
                successfulAnalysis(),
                null,
                null,
                null,
                "MOCK",
                null,
                "prompt-fingerprint",
                "taxonomy-fingerprint",
                context.username(),
                context,
                2L);
        var completed = persistenceService.completeJob(
                job.id(), project.id(), scopeKey);

        assertThat(accepted.id()).isEqualTo(currentSnapshotId);
        assertThat(accepted.requirementVersionId()).isEqualTo(retryVersion.id());
        assertThat(completed.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(completed.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(AnalysisStatus.SUCCESS);
            assertThat(item.attempt()).isEqualTo(2);
            assertThat(item.snapshotId()).isEqualTo(currentSnapshotId);
        });
    }

    private static AnalysisResult successfulAnalysis() {
        AnalysisResult analysis = new AnalysisResult(Map.of("BP-1000", 90), List.of());
        analysis.setStatus("SUCCESS");
        return analysis;
    }

    private com.taxonomy.portfolio.dto.PortfolioDtos.ProjectView createProject(
            WorkspaceContext context) {
        return projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(),
                        "Claim generation project",
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
            Long projectId,
            WorkspaceContext context) {
        return projectService.createRequirement(
                projectId,
                new CreateRequirementRequest(
                        "REQ-001",
                        "Claim generation requirement",
                        "Initial requirement text",
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
    }

    private static WorkspaceContext context() {
        String suffix = shortId().toLowerCase();
        String username = "claim-generation-" + suffix;
        return new WorkspaceContext(username, "ws-" + suffix, "draft");
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
