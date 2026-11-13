package com.taxonomy.portfolio;

import com.taxonomy.dto.AnalysisLanguage;
import com.taxonomy.dto.AnalysisMode;
import com.taxonomy.dto.AnalysisModelBinding;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.portfolio.controller.ProjectAnalysisController;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisModelSelection;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ProjectAnalysisControllerAsyncContractTest {

    @Mock private ProjectRequirementAnalysisService analysisService;
    @Mock private PortfolioAnalysisPersistenceService persistenceService;
    @Mock private WorkspaceResolver workspaceResolver;

    private ProjectAnalysisController controller;
    private final WorkspaceContext context = new WorkspaceContext("architect", "ws-architect", "draft", "architecture-repo");

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(context.username(), null, "ROLE_ARCHITECT"));
        org.mockito.Mockito.lenient().when(workspaceResolver.resolveCurrentUsername()).thenReturn(context.username());
        org.mockito.Mockito.lenient().when(workspaceResolver.resolveCurrentContext()).thenReturn(context);
        org.mockito.Mockito.lenient().when(workspaceResolver.resolveUnambiguousContextForRead()).thenReturn(context);
        controller = new ProjectAnalysisController(analysisService, persistenceService, workspaceResolver);
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
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
    void readsTheRecordedModelInTheAuthenticatedProjectContext() throws Exception {
        String commit = "b".repeat(40);
        String generation = "10000000-0000-4000-8000-000000000001";
        var model = new AnalysisModelBinding(AnalysisModelBinding.State.RESOLVED,
                context.repositoryId(), context.workspaceId(), context.currentBranch(), commit, 4L,
                "WORKSPACE_REVISION", "a".repeat(64), generation, "catalogue-v1", Set.of("CP"),
                new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY), "brief-v1",
                Map.of(), List.of(), Set.of(), null, generation);
        when(persistenceService.admittedModel("job-1", 41L, context.username(), context)).thenReturn(model);

        standaloneSetup(controller).build().perform(get("/api/projects/41/analysis-jobs/job-1/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(context.repositoryId()))
                .andExpect(jsonPath("$.workspaceScopeKey").value(context.workspaceId()))
                .andExpect(jsonPath("$.branch").value(context.currentBranch()))
                .andExpect(jsonPath("$.commit").value(commit))
                .andExpect(jsonPath("$.semanticRevision").value(4))
                .andExpect(jsonPath("$.executionCatalogueRevision").value(generation))
                .andExpect(jsonPath("$.scope.taxonomyRoots[0]").value("CP"))
                .andExpect(jsonPath("$.scope.mode").value("TAXONOMIES_ONLY"));
        verify(persistenceService).admittedModel("job-1", 41L, context.username(), context);
    }

    @Test
    void historicalJobWithoutAnAdmittedModelReturnsNoContent() throws Exception {
        when(persistenceService.admittedModel("legacy", 41L, context.username(), context)).thenReturn(null);

        standaloneSetup(controller).build().perform(get("/api/projects/41/analysis-jobs/legacy/model"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(persistenceService).admittedModel("legacy", 41L, context.username(), context);
    }

    @ParameterizedTest
    @CsvSource({"31,false", ",true", "31,true"})
    void explicitSelectionQueuesOnlyTheRequirementInThePath(Long version, boolean selectModel) throws Exception {
        String commit = "b".repeat(40);
        var selection = selectModel ? new AnalysisModelSelection(commit, 4L,
                new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY)) : null;
        var expected = new AnalyzeProjectRequest(List.of(7L), false, "MOCK", 25, "pinned",
                AnalysisLanguage.GERMAN, version, selection);
        when(analysisService.enqueueProject(41L, expected, context.username(), context)).thenReturn(pendingJob());
        String modelJson = selectModel ? """
                {"expectedCommit":"%s","expectedSemanticRevision":4,
                 "scope":{"taxonomyRoots":["CP"],"mode":"TAXONOMIES_ONLY"}}
                """.formatted(commit) : "null";
        var request = post("/api/projects/41/requirements/7/analyses").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requirementIds":[99],"all":true,"provider":"MOCK","maxArchitectureNodes":25,
                         "idempotencyKey":"pinned","analysisLanguage":"de","requirementVersionId":%s,"model":%s}
                        """.formatted(version, modelJson));

        standaloneSetup(controller).build().perform(request)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/projects/41/analysis-jobs/job-1"))
                .andExpect(jsonPath("$.id").value("job-1"));
        verify(analysisService).enqueueProject(41L, expected, context.username(), context);
        verifyNoMoreInteractions(analysisService);
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
