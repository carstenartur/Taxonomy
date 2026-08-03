package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.ProjectAnalysisController;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectAnalysisControllerAsyncContractTest {

    @Mock private ProjectRequirementAnalysisService analysisService;
    @Mock private PortfolioAnalysisPersistenceService persistenceService;
    @Mock private WorkspaceResolver workspaceResolver;

    private ProjectAnalysisController controller;
    private final WorkspaceContext context = new WorkspaceContext("architect", "ws-architect", "draft");

    @BeforeEach
    void setUp() {
        controller = new ProjectAnalysisController(
                analysisService, persistenceService, workspaceResolver);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

    @Test
    void projectAnalysisReturnsAcceptedAndCanonicalJobLocation() {
        AnalysisJobView job = pendingJob();
        AnalyzeProjectRequest request = new AnalyzeProjectRequest(
                List.of(7L), false, "MOCK", 25, "client-key");
        when(analysisService.enqueueProject(
                eq(41L), eq(request), eq(context.username()), eq(context)))
                .thenReturn(job);

        var response = controller.analyzeProject(41L, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/projects/41/analysis-jobs/job-1");
        assertThat(response.getBody()).isSameAs(job);
    }

    @Test
    void singleRequirementAnalysisAlsoUsesThePersistedJobResource() {
        AnalysisJobView job = pendingJob();
        when(analysisService.enqueueRequirement(
                eq(41L), eq(7L), eq("MOCK"), eq(25), eq("single-key"),
                eq(context.username()), eq(context)))
                .thenReturn(job);

        var response = controller.analyzeRequirement(
                41L,
                7L,
                new AnalyzeProjectRequest(List.of(), false, "MOCK", 25, "single-key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/api/projects/41/analysis-jobs/job-1");
    }

    private AnalysisJobView pendingJob() {
        return new AnalysisJobView(
                "job-1",
                41L,
                AnalysisStatus.PENDING,
                "client-key",
                "MOCK",
                25,
                context.username(),
                context.workspaceId(),
                Instant.now(),
                null,
                null,
                1,
                0,
                0,
                0,
                null,
                List.of());
    }
}
