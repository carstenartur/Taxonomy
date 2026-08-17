package com.taxonomy.portfolio.service;

import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.ArchitectureRecommendation;
import com.taxonomy.dto.GapAnalysisView;
import com.taxonomy.dto.PatternDetectionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.versioning.service.HypothesisService;
import com.taxonomy.workspace.service.RepositoryContext;
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
 * newer requirement version, overwrite a retry, or mark it failed.</p>
 *
 * <p>Finalization takes a pessimistic row lock before validating the generation.
 * Provisional relation hypotheses are persisted only after that lock is held and
 * through the exact selected repository/workspace/branch context; their Git
 * projection is registered for {@code afterCommit}. The immutable snapshot and
 * its hypothesis links therefore commit first, while a rollback leaves neither a
 * snapshot nor a canonical hypothesis Git commit. The exact-tenant recovery
 * update must wait on the same item row.</p>
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
    private final HypothesisService hypothesisService;

    public PortfolioAnalysisClaimPersistenceService(
            RequirementAnalysisJobItemRepository itemRepository,
            PortfolioAnalysisPersistenceService persistenceService,
            HypothesisService hypothesisService) {
        this.itemRepository = itemRepository;
        this.persistenceService = persistenceService;
        this.hypothesisService = hypothesisService;
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
        persistDeferredHypotheses(
                analysis, analysisSessionId, username, context);
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

    private void persistDeferredHypotheses(AnalysisResult analysis,
                                           String analysisSessionId,
                                           String username,
                                           WorkspaceContext context) {
        if (analysis == null
                || analysis.getProvisionalRelations() == null
                || analysis.getProvisionalRelations().isEmpty()) {
            return;
        }
        if (analysisSessionId == null || analysisSessionId.isBlank()) {
            throw PortfolioException.validation(
                    "Analysis session ID is required for deferred hypotheses");
        }
        hypothesisService.persistFromAnalysisAfterCommit(
                analysis.getProvisionalRelations(),
                analysisSessionId.strip(),
                exactRepositoryContext(username, context));
    }

    private static RepositoryContext exactRepositoryContext(
            String username,
            WorkspaceContext context) {
        String repositoryId = PortfolioScope.repositoryId(context);
        String branch = PortfolioScope.branch(context);
        String effectiveUsername = PortfolioScope.username(username, context);
        String workspaceId = PortfolioScope.workspaceId(context);
        return workspaceId == null
                ? RepositoryContext.centralWrite(
                        repositoryId, branch, effectiveUsername)
                : RepositoryContext.workspace(
                        repositoryId, workspaceId, branch, effectiveUsername);
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
                .findClaimForUpdate(
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
