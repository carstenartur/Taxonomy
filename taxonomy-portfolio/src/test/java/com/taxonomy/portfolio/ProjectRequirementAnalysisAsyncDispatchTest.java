package com.taxonomy.portfolio;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.service.ArchitectureGapService;
import com.taxonomy.architecture.service.ArchitecturePatternService;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.service.PortfolioAnalysisClaimPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisPersistenceService;
import com.taxonomy.portfolio.service.PortfolioAnalysisRecoveryService;
import com.taxonomy.portfolio.service.PortfolioAnalysisWorkQueue;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectRequirementAnalysisAsyncDispatchTest {

    @Mock private ProjectPortfolioService projectService;
    @Mock private PortfolioAnalysisPersistenceService persistenceService;
    @Mock private PortfolioAnalysisClaimPersistenceService claimPersistenceService;
    @Mock private PortfolioAnalysisWorkQueue workQueue;
    @Mock private PortfolioAnalysisRecoveryService recoveryService;
    @Mock private AnalyzeRequirementUseCase analyzeRequirementUseCase;
    @Mock private ArchitectureGapService gapService;
    @Mock private ArchitecturePatternService patternService;
    @Mock private ArchitectureRecommendationService recommendationService;
    @Mock private PortfolioFingerprintService fingerprintService;
    @Mock private LlmService llmService;
    @Mock private AsyncTaskExecutor analysisExecutor;

    private ProjectRequirementAnalysisService service;
    private final WorkspaceContext context = new WorkspaceContext("architect", "ws-architect", "draft");

    @BeforeEach
    void setUp() {
        service = new ProjectRequirementAnalysisService(
                projectService,
                persistenceService,
                claimPersistenceService,
                workQueue,
                recoveryService,
                analyzeRequirementUseCase,
                gapService,
                patternService,
                recommendationService,
                fingerprintService,
                llmService,
                analysisExecutor,
                100,
                100,
                900);
        lenient().when(persistenceService.createOrReuseJob(
                anyLong(), anyList(), anyString(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(job(AnalysisStatus.PENDING));
    }

    @Test
    void enqueueReturnsPersistedJobWithoutCallingTheProviderInTheRequestThread() {
        AnalysisJobView job = service.enqueueProject(
                41L,
                new AnalyzeProjectRequest(List.of(7L), false, "mock", 25, "client-key"),
                context.username(),
                context);

        assertThat(job.status()).isEqualTo(AnalysisStatus.PENDING);
        verify(analysisExecutor).execute(any(Runnable.class));
        verifyNoInteractions(analyzeRequirementUseCase, claimPersistenceService);
    }

    @Test
    void saturatedExecutorReturnsTypedServiceUnavailableFailureAndKeepsTheJobPersisted() {
        doThrow(new TaskRejectedException("worker queue full"))
                .when(analysisExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.enqueueProject(
                41L,
                new AnalyzeProjectRequest(List.of(7L), false, "MOCK", 25, "client-key"),
                context.username(),
                context))
                .isInstanceOf(PortfolioException.class)
                .satisfies(failure -> assertThat(((PortfolioException) failure).getKind())
                        .isEqualTo(PortfolioException.Kind.UNAVAILABLE))
                .hasMessageContaining("persisted job job-1");

        verifyNoInteractions(analyzeRequirementUseCase, claimPersistenceService);
    }

    @Test
    void rejectedRecoveredJobCanBeSubmittedAgainWithoutAnotherRecovery() {
        when(persistenceService.getJob(
                "job-1", 41L, context.username(), context))
                .thenReturn(job(AnalysisStatus.FAILED), job(AnalysisStatus.PENDING),
                        job(AnalysisStatus.PENDING));
        doThrow(new TaskRejectedException("worker queue full"))
                .doNothing()
                .when(analysisExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.enqueueRetryFailed(
                "job-1", 41L, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("persisted job job-1");

        AnalysisJobView redispatched = service.enqueueRetryFailed(
                "job-1", 41L, context.username(), context);

        assertThat(redispatched.status()).isEqualTo(AnalysisStatus.PENDING);
        verify(recoveryService, times(1)).prepareRetryableItems(
                anyString(), anyLong(), anyString(), any(), any());
        verify(analysisExecutor, times(2)).execute(any(Runnable.class));
        verifyNoInteractions(analyzeRequirementUseCase, claimPersistenceService);
    }

    @Test
    void claimedPortfolioItemWaitsForTelemetryCapacityAndPersistsWithoutFailure() throws Exception {
        var registry = new com.taxonomy.analysis.service.AnalysisProgressRegistry(
                new org.springframework.core.env.StandardEnvironment());
        var architecture = org.mockito.Mockito.mock(
                com.taxonomy.architecture.service.RequirementArchitectureViewService.class);
        var relations = org.mockito.Mockito.mock(com.taxonomy.analysis.service.AnalysisRelationGenerator.class);
        var repositoryState = org.mockito.Mockito.mock(com.taxonomy.versioning.service.RepositoryStateService.class);
        var preferences = org.mockito.Mockito.mock(com.taxonomy.architecture.service.ArchitectureReportMetadataPort.class);
        var useCase = new AnalyzeRequirementUseCase(llmService,
                org.mockito.Mockito.mock(com.taxonomy.analysis.service.AiPromptBudgetPolicy.class),
                architecture, relations, org.mockito.Mockito.mock(com.taxonomy.relations.service.HypothesisService.class),
                repositoryState, preferences);
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "analysisProgressRegistry", registry);
        service = new ProjectRequirementAnalysisService(projectService, persistenceService,
                claimPersistenceService, workQueue, recoveryService, useCase, gapService,
                patternService, recommendationService, fingerprintService, llmService,
                analysisExecutor, 100, 100, 900);
        var result = new com.taxonomy.dto.AnalysisResult();
        result.setStatus("SUCCESS");
        result.setScores(java.util.Map.of("CP", 80));
        when(llmService.analyzeWithBudget(anyString())).thenReturn(result);
        when(relations.generate(any())).thenReturn(List.of());
        when(architecture.build(any(), anyString(), anyInt(), any()))
                .thenReturn(new com.taxonomy.dto.RequirementArchitectureView());
        when(preferences.resolve()).thenReturn(com.taxonomy.export.DiagramViewMetadata.fromConfig(
                com.taxonomy.export.DiagramSelectionConfig.trace(), "trace"));
        when(persistenceService.getJob("job-1", 41L, context.username(), context))
                .thenReturn(job(AnalysisStatus.PENDING));
        when(persistenceService.completeJob(anyString(), anyLong(), anyString()))
                .thenReturn(job(AnalysisStatus.SUCCESS));
        var item = new PortfolioAnalysisWorkQueue.WorkItem(1L, "job-1", 41L, "tenant-scope",
                7L, "REQ-001", 8L, 1, "Resilient communications");
        var claimed = new java.util.concurrent.CountDownLatch(1);
        when(workQueue.pending(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            claimed.countDown();
            return List.of(item);
        });
        var reservations = new java.util.ArrayList<com.taxonomy.analysis.service.AnalysisProgressRegistry.Reservation>();
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                for (int i = 0; i < 4; i++) reservations.add(registry.reserve(null, context.username(), context, null));
                var running = worker.submit(() -> service.analyzeProject(41L,
                        new AnalyzeProjectRequest(List.of(7L), false, "MOCK", 25, "client-key"),
                        context.username(), context));
                org.junit.jupiter.api.Assertions.assertTrue(claimed.await(5, java.util.concurrent.TimeUnit.SECONDS));
                org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> running.get(100, java.util.concurrent.TimeUnit.MILLISECONDS));
                verifyNoInteractions(llmService, claimPersistenceService);
                reservations.removeFirst().close();
                assertThat(running.get(5, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(AnalysisStatus.SUCCESS);
                verify(claimPersistenceService, org.mockito.Mockito.never()).failItem(any(), any());
                verify(claimPersistenceService).persistSnapshot(org.mockito.ArgumentMatchers.eq(item),
                        anyString(), anyString(), org.mockito.ArgumentMatchers.same(result), any(), any(), any(),
                        org.mockito.ArgumentMatchers.eq("MOCK"), org.mockito.ArgumentMatchers.isNull(), any(), any(),
                        org.mockito.ArgumentMatchers.eq(context.username()), org.mockito.ArgumentMatchers.eq(context), anyLong());
            } finally {
                reservations.forEach(com.taxonomy.analysis.service.AnalysisProgressRegistry.Reservation::close);
                worker.shutdownNow();
            }
        }
    }

    private AnalysisJobView job(AnalysisStatus status) {
        return new AnalysisJobView(
                "job-1",
                41L,
                status,
                "client-key",
                "MOCK",
                25,
                context.username(),
                context.workspaceId(),
                Instant.now(),
                null,
                null,
                1,
                status == AnalysisStatus.SUCCESS ? 1 : 0,
                0,
                status == AnalysisStatus.FAILED ? 1 : 0,
                null,
                List.of());
    }
}
