package com.taxonomy.portfolio;

import com.taxonomy.portfolio.controller.ProjectAnalysisController;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisContinuationView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ContinueAnalysisRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ProjectAnalysisControllerAsyncContractTest {

    @Mock private ProjectRequirementAnalysisService analysisService;
    @Mock private PortfolioAnalysisPersistenceService persistenceService;
    @Mock private WorkspaceResolver workspaceResolver;

    private ProjectAnalysisController controller;
    private final WorkspaceContext context = new WorkspaceContext("architect", "ws-architect", "draft");

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        controller = new ProjectAnalysisController(
                analysisService, persistenceService, workspaceResolver);
        when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @CsvSource({
            "'', '', /api/projects/41/analysis-jobs/job-1",
            "/taxonomy, '', /taxonomy/api/projects/41/analysis-jobs/job-1",
            "/tenant/taxonomy, '', /tenant/taxonomy/api/projects/41/analysis-jobs/job-1",
            "/internal, /edge/taxonomy, /edge/taxonomy/api/projects/41/analysis-jobs/job-1"
    })
    void acceptedLocationRetainsTheEffectiveApplicationPrefix(
            String contextPath, String forwardedPrefix, String expectedLocation) throws Exception {
        var body = new AnalyzeProjectRequest(List.of(7L), false, "MOCK", 25, "client-key");
        when(analysisService.enqueueProject(41L, body, context.username(), context)).thenReturn(pendingJob());
        var request = post(contextPath + "/api/projects/41/analyses")
                .contextPath(contextPath)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requirementIds":[7],"all":false,"provider":"MOCK","maxArchitectureNodes":25,
                         "idempotencyKey":"client-key"}
                        """);
        if (!forwardedPrefix.isEmpty()) request.header("X-Forwarded-Prefix", forwardedPrefix);

        standaloneSetup(controller).addFilters(new ForwardedHeaderFilter()).build()
                .perform(request)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", expectedLocation));
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

    @Test
    void continuationReadOnlyObservesTheExactAuthorizedItem() {
        var decision = new AnalysisContinuationView("snapshot", 9L, "run", 123L, "STOPPED", true, "Budget stop");
        when(analysisService.getContinuation("job-1", 41L, 7L, context.username(), context)).thenReturn(decision);
        assertThat(controller.getContinuation(41L, "job-1", 7L)).isSameAs(decision);
        verify(analysisService).getContinuation("job-1", 41L, 7L, context.username(), context);
        verifyNoMoreInteractions(analysisService);
    }

    @Test
    void explicitContinuationReturnsTheExistingJobWithoutStartingAnotherVerificationPass() {
        var request = new ContinueAnalysisRequest("snapshot", 123L);
        var job = pendingJob();
        when(analysisService.enqueueContinuation("job-1", 41L, 7L, request, context.username(), context)).thenReturn(job);
        var response = controller.continueAnalysis(41L, "job-1", 7L, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/projects/41/analysis-jobs/job-1");
        assertThat(response.getBody()).isSameAs(job);
        verify(analysisService).enqueueContinuation("job-1", 41L, 7L, request, context.username(), context);
        verifyNoMoreInteractions(analysisService);
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
