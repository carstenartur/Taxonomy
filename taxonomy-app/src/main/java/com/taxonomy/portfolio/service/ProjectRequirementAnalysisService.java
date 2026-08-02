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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Orchestrates independent analyses while keeping LLM calls outside persistence transactions. */
@Service
public class ProjectRequirementAnalysisService {

    private static final int DEFAULT_ARCHITECTURE_NODES = 25;
    private static final int INTELLIGENCE_MIN_SCORE = 50;

    private final ProjectPortfolioService projectService;
    private final PortfolioAnalysisPersistenceService persistenceService;
    private final PortfolioAnalysisWorkQueue workQueue;
    private final AnalyzeRequirementUseCase analyzeRequirementUseCase;
    private final ArchitectureGapService gapService;
    private final ArchitecturePatternService patternService;
    private final ArchitectureRecommendationService recommendationService;
    private final PortfolioFingerprintService fingerprintService;
    private final LlmService llmService;
    private final int maximumArchitectureNodes;

    public ProjectRequirementAnalysisService(ProjectPortfolioService projectService,
                                             PortfolioAnalysisPersistenceService persistenceService,
                                             PortfolioAnalysisWorkQueue workQueue,
                                             AnalyzeRequirementUseCase analyzeRequirementUseCase,
                                             ArchitectureGapService gapService,
                                             ArchitecturePatternService patternService,
                                             ArchitectureRecommendationService recommendationService,
                                             PortfolioFingerprintService fingerprintService,
                                             LlmService llmService,
                                             @Value("${taxonomy.limits.max-architecture-nodes:100}")
                                             int maximumArchitectureNodes) {
        this.projectService = projectService;
        this.persistenceService = persistenceService;
        this.workQueue = workQueue;
        this.analyzeRequirementUseCase = analyzeRequirementUseCase;
        this.gapService = gapService;
        this.patternService = patternService;
        this.recommendationService = recommendationService;
        this.fingerprintService = fingerprintService;
        this.llmService = llmService;
        this.maximumArchitectureNodes = Math.max(1, maximumArchitectureNodes);
    }

    public AnalysisJobView analyzeProject(Long projectId,
                                          AnalyzeProjectRequest request,
                                          String username,
                                          WorkspaceContext context) {
        if (request == null) throw PortfolioException.validation("analysis request is required");
        projectService.requireProject(projectId, username, context);

        List<Long> requirementIds = selectRequirementIds(projectId, request, username, context);
        int maxNodes = normalizeMaxNodes(request.maxArchitectureNodes());
        AnalysisJobView job = persistenceService.createOrReuseJob(
                projectId,
                requirementIds,
                normalizeProvider(request.provider()),
                maxNodes,
                request.idempotencyKey(),
                username,
                context);
        if (job.status() != AnalysisStatus.PENDING) {
            return job;
        }
        return executePendingItems(job.id(), projectId, username, context);
    }

    public AnalysisJobView analyzeRequirement(Long projectId,
                                              Long requirementId,
                                              String provider,
                                              Integer maxArchitectureNodes,
                                              String idempotencyKey,
                                              String username,
                                              WorkspaceContext context) {
        return analyzeProject(projectId,
                new AnalyzeProjectRequest(
                        List.of(requirementId), false, provider, maxArchitectureNodes, idempotencyKey),
                username,
                context);
    }

    public AnalysisJobView retryFailed(String jobId,
                                       Long projectId,
                                       String username,
                                       WorkspaceContext context) {
        persistenceService.prepareFailedItemsForRetry(jobId, projectId, username, context);
        return executePendingItems(jobId, projectId, username, context);
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

    private AnalysisJobView executePendingItems(String jobId,
                                                Long projectId,
                                                String username,
                                                WorkspaceContext context) {
        persistenceService.markJobRunning(jobId, projectId);
        AnalysisJobView job = persistenceService.getJob(jobId, projectId, username, context);
        String taxonomyFingerprint = fingerprintService.taxonomyFingerprint();
        String promptFingerprint = fingerprintService.promptFingerprint();
        String effectiveProvider = job.provider() != null
                ? job.provider() : llmService.getActiveProviderName();

        for (PortfolioAnalysisWorkQueue.WorkItem workItem : workQueue.pending(jobId, projectId)) {
            persistenceService.markItemRunning(workItem.itemId());
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
            return ids;
        }
        if (request.requirementIds() == null || request.requirementIds().isEmpty()) {
            throw PortfolioException.validation("requirementIds must be supplied unless all=true");
        }
        return request.requirementIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
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
