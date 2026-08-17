package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Commits a worker result only while the exact claim generation is still active.
 *
 * <p>The short claim transaction returns both the immutable requirement version
 * and the retry attempt. A recovery increments the attempt before another worker
 * can claim the row. Late workers therefore cannot attach an old analysis to a
 * newer requirement version, overwrite a retry, or mark it failed. The item is
 * loaded in this outer transaction before delegating to the normal persistence
 * service, so its optimistic row version also closes a recovery race occurring
 * after validation but before flush.</p>
 *
 * <p>The item is the claim authority. The job status is only an aggregate and
 * may temporarily return to {@code PENDING} when another item is recovered. A
 * valid RUNNING item must remain completable in that state, while terminal or
 * cancelled jobs reject further worker writes.</p>
 */
@Service
public class PortfolioAnalysisClaimPersistenceService {

    private final RequirementAnalysisJobItemRepository itemRepository;
    private final PortfolioAnalysisPersistenceService persistenceService;

    public PortfolioAnalysisClaimPersistenceService(
            RequirementAnalysisJobItemRepository itemRepository,
            PortfolioAnalysisPersistenceService persistenceService) {
        this.itemRepository = itemRepository;
        this.persistenceService = persistenceService;
    }

    @Transactional
    public SnapshotSummary persistSnapshot(
            PortfolioAnalysisWorkQueue.WorkItem workItem,
            String snapshotId,
            String analysisSessionId,
            AnalysisResult analysis,
            GapAnalysisView gaps,
            PatternDetectionView patterns,
            ArchitectureRecommendation recommendation,
            String provider,
            String modelName,
            String promptFingerprint,
            String taxonomyFingerprint,
            String username,
            WorkspaceContext context,
            long durationMs) {
        requireActiveClaim(workItem);
        return persistenceService.persistSnapshot(
                workItem.itemId(),
                workItem.jobId(),
                workItem.projectId(),
                workItem.scopeKey(),
                snapshotId,
                analysisSessionId,
                analysis,
                gaps,
                patterns,
                recommendation,
                provider,
                modelName,
                promptFingerprint,
                taxonomyFingerprint,
                username,
                context,
                durationMs);
    }

    @Transactional
    public void failItem(PortfolioAnalysisWorkQueue.WorkItem workItem,
                         Throwable failure) {
        requireActiveClaim(workItem);
        persistenceService.failItem(
                workItem.itemId(),
                workItem.jobId(),
                workItem.projectId(),
                workItem.scopeKey(),
                failure);
    }

    private RequirementAnalysisJobItem requireActiveClaim(
            PortfolioAnalysisWorkQueue.WorkItem workItem) {
        if (workItem == null
                || workItem.itemId() == null
                || workItem.jobId() == null
                || workItem.projectId() == null
                || workItem.scopeKey() == null
                || workItem.scopeKey().isBlank()
                || workItem.requirementId() == null
                || workItem.requirementVersionId() == null
                || workItem.attempt() < 1) {
            throw PortfolioException.validation(
                    "Complete analysis claim identity is required");
        }

        RequirementAnalysisJobItem item = itemRepository
                .findByIdAndJobIdAndProjectIdAndScopeKey(
                        workItem.itemId(),
                        workItem.jobId(),
                        workItem.projectId(),
                        workItem.scopeKey().strip())
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis job item not found: " + workItem.itemId()));

        AnalysisStatus jobStatus = item.getJob().getStatus();
        boolean jobAcceptsWorkerResult = jobStatus == AnalysisStatus.RUNNING
                || jobStatus == AnalysisStatus.PENDING;
        boolean active = item.getStatus() == AnalysisStatus.RUNNING
                && jobAcceptsWorkerResult
                && item.getAttempt() == workItem.attempt()
                && Objects.equals(item.getRequirementId(), workItem.requirementId())
                && Objects.equals(
                        item.getRequirementVersionId(),
                        workItem.requirementVersionId());
        if (!active) {
            throw PortfolioException.conflict(
                    "Analysis work item claim is no longer active: "
                            + workItem.itemId());
        }
        return item;
    }
}
