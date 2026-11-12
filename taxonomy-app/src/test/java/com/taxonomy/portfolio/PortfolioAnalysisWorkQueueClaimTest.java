package com.taxonomy.portfolio;

import org.junit.jupiter.api.Tag;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Tag("ci-app-shard-domain-integration")
class PortfolioAnalysisWorkQueueClaimTest extends RetainedPortfolioCatalogue {

    @Autowired
    private WorkspaceResolver workspaceResolver;

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private PortfolioAnalysisPersistenceService persistenceService;

    @Autowired
    private PortfolioAnalysisWorkQueue workQueue;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void pendingWorkItemCanBeClaimedOnlyOnceWithoutPrematureJobCompletion() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        WorkspaceContext context = PortfolioAnalysisWorkspace.select(workspaceResolver, "claim-" + suffix.toLowerCase());
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
        String scopeKey = PortfolioScope.key(context.username(), context);

        // Fail the parent UPDATE at commit after the child claim has been issued.
        String constraint = "ck_claim_" + suffix;
        jdbc.execute("alter table req_analysis_job add constraint " + constraint
                + " check (id <> '" + UUID.fromString(job.id()) + "' or status <> 'RUNNING')");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> workQueue.pending(job.id(), project.id(), scopeKey))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            var rolledBack = persistenceService.getJob(job.id(), project.id(), context.username(), context);
            assertThat(rolledBack.status()).isEqualTo(AnalysisStatus.PENDING);
            assertThat(rolledBack.items()).singleElement()
                    .satisfies(item -> assertThat(item.status()).isEqualTo(AnalysisStatus.PENDING));
        } finally {
            jdbc.execute("alter table req_analysis_job drop constraint " + constraint);
        }
        var firstClaim = workQueue.pending(job.id(), project.id(), scopeKey);
        var justClaimed = persistenceService.getJob(job.id(), project.id(), context.username(), context);
        assertThat(justClaimed.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(justClaimed.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo(AnalysisStatus.RUNNING);
            assertThat(item.startedAt()).isEqualTo(justClaimed.startedAt());
        });
        var prematureCompletion = persistenceService.completeJob(
                job.id(), project.id(), scopeKey);
        var secondClaim = workQueue.pending(job.id(), project.id(), scopeKey);

        assertThat(firstClaim).singleElement()
                .satisfies(item -> {
                    assertThat(item.requirementId()).isEqualTo(requirement.id());
                    assertThat(item.projectId()).isEqualTo(project.id());
                    assertThat(item.scopeKey()).isEqualTo(scopeKey);
                });
        assertThat(secondClaim).isEmpty();
        assertThat(prematureCompletion.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(prematureCompletion.completedAt()).isNull();
        assertThat(persistenceService.getJob(
                job.id(), project.id(), context.username(), context).items())
                .singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo(AnalysisStatus.RUNNING));
    }

    @Test
    void pendingCandidateQueryFetchesTheCompleteWorkerPayload() throws Exception {
        var method = RequirementAnalysisJobItemRepository.class.getMethod(
                "findByJobIdAndProjectIdAndScopeKeyAndStatusOrderByRequirementRequirementKeyAsc",
                String.class,
                Long.class,
                String.class,
                AnalysisStatus.class);

        EntityGraph graph = method.getAnnotation(EntityGraph.class);

        assertThat(graph).isNotNull();
        assertThat(graph.attributePaths())
                .containsExactlyInAnyOrder("requirement", "requirementVersion");
    }
}
