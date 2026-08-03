package com.taxonomy.portfolio;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.service.ArchitectureGapService;
import com.taxonomy.architecture.service.ArchitecturePatternService;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectRequirementAnalysisAsyncDispatchTest {

    @Mock private ProjectPortfolioService projectService;
    @Mock private PortfolioAnalysisPersistenceService persistenceService;
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
        when(persistenceService.createOrReuseJob(
                anyLong(), anyList(), anyString(), anyInt(), anyString(), anyString(), any()))
                .thenReturn(pendingJob());
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
        verifyNoInteractions(analyzeRequirementUseCase);
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

        verifyNoInteractions(analyzeRequirementUseCase);
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
