package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.architecture.service.ArchitectureGapService;
import com.taxonomy.architecture.service.ArchitecturePatternService;
import com.taxonomy.architecture.service.ArchitectureRecommendationService;
import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalyzeProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDiff;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Orchestrates independent analyses while keeping LLM calls outside persistence transactions. */
@Service
public class ProjectRequirementAnalysisService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProjectRequirementAnalysisService.class);
    private static final int DEFAULT_ARCHITECTURE_NODES = 25;
    private static final int INTELLIGENCE_MIN_SCORE = 50;

    private final ProjectPortfolioService projectService;
    private final PortfolioAnalysisPersistenceService persistenceService;
    private final PortfolioAnalysisWorkQueue workQueue;
    private final PortfolioAnalysisRecoveryService recoveryService;
    private final AnalyzeRequirementUseCase analyzeRequirementUseCase;
    private final ArchitectureGapService gapService;
    private final ArchitecturePatternService patternService;
    private final ArchitectureRecommendationService recommendationService;
    private final PortfolioFingerprintService fingerprintService;
    private final LlmService llmService;
    private final AsyncTaskExecutor analysisExecutor;
    private final int maximumArchitectureNodes;
    private final int maximumBatchRequirements;
    private final long claimTimeoutSeconds;

    public ProjectRequirementAnalysisService(ProjectPortfolioService projectService,
                                             PortfolioAnalysisPersistenceService persistenceService,
                                             PortfolioAnalysisWorkQueue workQueue,
                                             PortfolioAnalysisRecoveryService recoveryService,
                                             AnalyzeRequirementUseCase analyzeRequirementUseCase,
                                             ArchitectureGapService gapService,
                                             ArchitecturePatternService patternService,
                                             ArchitectureRecommendationService recommendationService,
                                             PortfolioFingerprintService fingerprintService,
                                             LlmService llmService,
                                             @Qualifier("portfolioAnalysisExecutor")
                                             AsyncTaskExecutor analysisExecutor,
                                             @Value("${taxonomy.limits.max-architecture-nodes:100}")
                                             int maximumArchitectureNodes,
                                             @Value("${taxonomy.portfolio.max-analysis-batch:100}")
                                             int maximumBatchRequirements,
                                             @Value("${taxonomy.portfolio.analysis-claim-timeout-seconds:900}")
                                             long claimTimeoutSeconds) {
        this.projectService = projectService;
        this.persistenceService = persistenceService;
        this.workQueue = workQueue;
        this.recoveryService = recoveryService;
        this.analyzeRequirementUseCase = analyzeRequirementUseCase;
        this.gapService = gapService;
        this.patternService = patternService;
        this.recommendationService = recommendationService;
        this.fingerprintService = fingerprintService;
        this.llmService = llmService;
        this.analysisExecutor = analysisExecutor;
        this.maximumArchitectureNodes = Math.max(1, maximumArchitectureNodes);
        this.maximumBatchRequirements = Math.max(1, maximumBatchRequirements);
        this.claimTimeoutSeconds = Math.max(60L, claimTimeoutSeconds);
    }

    /** Executes a persisted job in the caller thread. Kept for internal batch use and deterministic tests. */
    public AnalysisJobView analyzeProject(Long projectId,
                                          AnalyzeProjectRequest request,
                                          String username,
                                          WorkspaceContext context) {
        AnalysisJobView job = prepareJob(projectId, request, username, context);
        if (job.status() != AnalysisStatus.PENDING) {
            return job;
        }
        return executePendingItems(job.id(), projectId, username, context);
    }

    /** Persists and dispatches a job, returning before any LLM request is made. */
    public AnalysisJobView enqueueProject(Long projectId,
                                          AnalyzeProjectRequest request,
                                          String username,
                                          WorkspaceContext context) {
        AnalysisJobView job = prepareJob(projectId, request, username, context);
        if (job.status() == AnalysisStatus.PENDING) {
            dispatch(job.id(), projectId, username, context);
        }
        return job;
    }

    public AnalysisJobView analyzeRequirement(Long projectId,
                                              Long requirementId,
                                              String provider,
                                              Integer maxArchitectureNodes,
                                              String idempotencyKey,
                                              String username,
                                              WorkspaceContext context) {
        return analyzeProject(projectId,
                requestForRequirement(requirementId, provider, maxArchitectureNodes, idempotencyKey),
                username,
                context);
    }

    public AnalysisJobView enqueueRequirement(Long projectId,
                                              Long requirementId,
                                              String provider,
                                              Integer maxArchitectureNodes,
                                              String idempotencyKey,
                                              String username,
                                              WorkspaceContext context) {
        return enqueueProject(projectId,
                requestForRequirement(requirementId, provider, maxArchitectureNodes, idempotencyKey),
                username,
                context);
    }

    public AnalysisJobView retryFailed(String jobId,
                                       Long projectId,
                                       String username,
                                       WorkspaceContext context) {
        recoveryService.prepareRetryableItems(
                jobId,
                projectId,
                username,
                context,
                Instant.now().minusSeconds(claimTimeoutSeconds));
        return executePendingItems(jobId, projectId, username, context);
    }

    /** Re-dispatches pending jobs or recovers failed/expired claims without holding the HTTP request. */
    public AnalysisJobView enqueueRetryFailed(String jobId,
                                              Long projectId,
                                              String username,
                                              WorkspaceContext context) {
        AnalysisJobView job = persistenceService.getJob(jobId, projectId, username, context);
        if (job.status() != AnalysisStatus.PENDING) {
            recoveryService.prepareRetryableItems(
                    jobId,
                    projectId,
                    username,
                    context,
                    Instant.now().minusSeconds(claimTimeoutSeconds));
            job = persistenceService.getJob(jobId, projectId, username, context);
        }
        dispatch(jobId, projectId, username, context);
        return job;
    }

    public AnalysisJobView getJob(String jobId,
                                  Long projectId,
                                  String username,
                                  WorkspaceContext context) {
        return persistenceService.getJob(jobId, projectId, username, context);
    }

    public List<AnalysisJobView> listJobs(Long projectId,
                                          String username,
                                          WorkspaceContext context) {
        return persistenceService.listJobs(projectId, username, context);
    }

    public List<SnapshotSummary> listSnapshots(Long projectId,
                                               Long requirementId,
                                               String username,
                                               WorkspaceContext context) {
        return persistenceService.listRequirementSnapshots(
                projectId, requirementId, username, context);
    }

    public SnapshotDetail getSnapshot(Long projectId,
                                      String snapshotId,
                                      String username,
                                      WorkspaceContext context) {
        return persistenceService.getSnapshot(projectId, snapshotId, username, context);
    }

    public SnapshotDiff diffSnapshots(Long projectId,
                                      String olderSnapshotId,
                                      String newerSnapshotId,
                                      String username,
                                      WorkspaceContext context) {
        return persistenceService.diffSnapshots(
                projectId, olderSnapshotId, newerSnapshotId, username, context);
    }

    private AnalysisJobView prepareJob(Long projectId,
                                       AnalyzeProjectRequest request,
                                       String username,
                                       WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("analysis request is required");
        projectService.requireProject(projectId, username, context);

        List<Long> requirementIds = selectRequirementIds(projectId, request, username, context);
        int maxNodes = normalizeMaxNodes(request.maxArchitectureNodes());
        return persistenceService.createOrReuseJob(
                projectId,
                requirementIds,
                normalizeProvider(request.provider()),
                maxNodes,
                request.idempotencyKey(),
                username,
                context);
    }

    private static AnalyzeProjectRequest requestForRequirement(Long requirementId,
                                                               String provider,
                                                               Integer maxArchitectureNodes,
                                                               String idempotencyKey) {
        return new AnalyzeProjectRequest(
                List.of(requirementId), false, provider, maxArchitectureNodes, idempotencyKey);
    }

    private void dispatch(String jobId,
                          Long projectId,
                          String username,
                          WorkspaceContext context) {
        try {
            analysisExecutor.execute(() -> {
                try {
                    executePendingItems(jobId, projectId, username, context);
                } catch (RuntimeException failure) {
                    LOGGER.error("Asynchronous portfolio analysis job {} for project {} stopped unexpectedly",
                            jobId, projectId, failure);
                }
            });
        } catch (TaskRejectedException rejected) {
            throw PortfolioException.unavailable(
                    "Portfolio analysis capacity is currently exhausted; persisted job "
                            + jobId + " can be submitted again",
                    rejected);
        }
    }

    private AnalysisJobView executePendingItems(String jobId,
                                                Long projectId,
                                                String username,
                                                WorkspaceContext context) {
        AnalysisJobView job = persistenceService.getJob(jobId, projectId, username, context);
        String taxonomyFingerprint = fingerprintService.taxonomyFingerprint();
        String promptFingerprint = fingerprintService.promptFingerprint();
        String effectiveProvider = job.provider() != null
                ? job.provider() : llmService.getActiveProviderName();

        List<PortfolioAnalysisWorkQueue.WorkItem> workItems = workQueue.pending(jobId, projectId);
        if (workItems.isEmpty()) {
            return persistenceService.completeJob(jobId, projectId);
        }
        persistenceService.markJobRunning(jobId, projectId);

        for (PortfolioAnalysisWorkQueue.WorkItem workItem : workItems) {
            String snapshotId = UUID.randomUUID().toString();
            String analysisSessionId = "portfolio:" + snapshotId;
            long startedAt = System.nanoTime();
            try {
                AnalysisResult analysis = analyzeRequirementUseCase.analyze(
                        new AnalyzeRequirementCommand(
                                workItem.requirementText(),
                                true,
                                job.maxArchitectureNodes(),
                                job.provider(),
                                username,
                                context,
                                new AnalysisProvenance(
                                        projectId,
                                        workItem.requirementId(),
                                        snapshotId,
                                        analysisSessionId)))
                        .analysisResult();
                if (analysis == null || analysis.getScores() == null
                        || "ERROR".equalsIgnoreCase(analysis.getStatus())) {
                    throw new IllegalStateException(analysis != null && analysis.getErrorMessage() != null
                            ? analysis.getErrorMessage() : "Analysis produced no usable scores");
                }

                GapAnalysisView gaps = safeGapAnalysis(analysis, workItem.requirementText());
                PatternDetectionView patterns = safePatternAnalysis(analysis);
                ArchitectureRecommendation recommendation = safeRecommendation(
                        analysis, workItem.requirementText());
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
                persistenceService.persistSnapshot(
                        workItem.itemId(),
                        snapshotId,
                        analysisSessionId,
                        analysis,
                        gaps,
                        patterns,
                        recommendation,
                        effectiveProvider,
                        null,
                        promptFingerprint,
                        taxonomyFingerprint,
                        username,
                        context,
                        durationMs);
            } catch (Exception failure) {
                persistenceService.failItem(workItem.itemId(), failure);
            }
        }
        return persistenceService.completeJob(jobId, projectId);
    }

    private GapAnalysisView safeGapAnalysis(AnalysisResult analysis, String requirementText) {
        try {
            return gapService.analyze(analysis.getScores(), requirementText, INTELLIGENCE_MIN_SCORE);
        } catch (RuntimeException failure) {
            analysis.getWarnings().add("Gap analysis failed: " + safeMessage(failure));
            GapAnalysisView fallback = new GapAnalysisView();
            fallback.setBusinessText(requirementText);
            fallback.getNotes().add("Gap analysis unavailable for this snapshot: " + safeMessage(failure));
            return fallback;
        }
    }

    private PatternDetectionView safePatternAnalysis(AnalysisResult analysis) {
        try {
            return patternService.detectForScores(analysis.getScores(), INTELLIGENCE_MIN_SCORE);
        } catch (RuntimeException failure) {
            analysis.getWarnings().add("Pattern detection failed: " + safeMessage(failure));
            PatternDetectionView fallback = new PatternDetectionView();
            fallback.getNotes().add("Pattern detection unavailable for this snapshot: " + safeMessage(failure));
            return fallback;
        }
    }

    private ArchitectureRecommendation safeRecommendation(AnalysisResult analysis, String requirementText) {
        try {
            return recommendationService.recommend(
                    analysis.getScores(), requirementText, INTELLIGENCE_MIN_SCORE);
        } catch (RuntimeException failure) {
            analysis.getWarnings().add("Architecture recommendation failed: " + safeMessage(failure));
            ArchitectureRecommendation fallback = new ArchitectureRecommendation();
            fallback.setBusinessText(requirementText);
            fallback.getNotes().add("Recommendation unavailable for this snapshot: " + safeMessage(failure));
            return fallback;
        }
    }

    private List<Long> selectRequirementIds(Long projectId,
                                            AnalyzeProjectRequest request,
                                            String username,
                                            WorkspaceContext context) {
        if (request.all()) {
            List<Long> ids = projectService.listRequirements(projectId, username, context).stream()
                    .map(requirement -> requirement.id())
                    .toList();
            if (ids.isEmpty()) throw PortfolioException.validation("Project has no requirements to analyze");
            return requireAllowedBatch(ids);
        }
        if (request.requirementIds() == null || request.requirementIds().isEmpty()) {
            throw PortfolioException.validation("requirementIds must be supplied unless all=true");
        }
        List<Long> ids = request.requirementIds().stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw PortfolioException.validation("At least one valid requirementId is required");
        }
        return requireAllowedBatch(ids);
    }

    private List<Long> requireAllowedBatch(List<Long> requirementIds) {
        if (requirementIds.size() > maximumBatchRequirements) {
            throw PortfolioException.validation(
                    "Analysis batch contains " + requirementIds.size()
                            + " requirements; maximum is " + maximumBatchRequirements);
        }
        return requirementIds;
    }

    private int normalizeMaxNodes(Integer requested) {
        int value = requested != null ? requested : DEFAULT_ARCHITECTURE_NODES;
        if (value < 1 || value > maximumArchitectureNodes) {
            throw PortfolioException.validation(
                    "maxArchitectureNodes must be between 1 and " + maximumArchitectureNodes);
        }
        return value;
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return null;
        return provider.strip().toUpperCase(Locale.ROOT);
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message != null && !message.isBlank()
                ? message : failure.getClass().getSimpleName();
    }
}
