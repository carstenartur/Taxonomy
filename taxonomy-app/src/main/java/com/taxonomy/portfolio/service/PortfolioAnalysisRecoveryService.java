package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Recovers failed items and expired worker claims without crossing exact tenants. */
@Service
public class PortfolioAnalysisRecoveryService {

    private final RequirementAnalysisJobRepository jobRepository;
    private final RequirementAnalysisJobItemRepository itemRepository;
    private final ProjectPortfolioService projectService;

    public PortfolioAnalysisRecoveryService(RequirementAnalysisJobRepository jobRepository,
                                            RequirementAnalysisJobItemRepository itemRepository,
                                            ProjectPortfolioService projectService) {
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.projectService = projectService;
    }

    /**
     * Resets failed items and RUNNING items whose claim is older than the supplied
     * cutoff. Active claims remain untouched. Every successful compare-and-set
     * includes job, project and exact tenant identity, increments the attempt and
     * binds the retry to the requirement's current immutable text version. The
     * job remains PENDING until a worker actually claims its prepared items.
     *
     * @return number of items atomically prepared for another attempt
     */
    @Transactional
    public int prepareRetryableItems(String jobId,
                                     Long projectId,
                                     String username,
                                     WorkspaceContext context,
                                     Instant staleBefore) {
        if (staleBefore == null) {
            throw PortfolioException.validation("staleBefore is required");
        }
        projectService.requireProject(projectId, username, context);
        String scopeKey = PortfolioScope.key(username, context);
        RequirementAnalysisJob job = requireJob(jobId, projectId, scopeKey);

        List<RequirementAnalysisJobItem> failed = itemRepository
                .findByJobIdAndProjectIdAndScopeKeyAndStatusOrderByRequirementRequirementKeyAsc(
                        jobId, projectId, scopeKey, AnalysisStatus.FAILED);
        List<RequirementAnalysisJobItem> stale = itemRepository
                .findByJobIdAndProjectIdAndScopeKeyAndStatusAndStartedAtBeforeOrderByRequirementRequirementKeyAsc(
                        jobId, projectId, scopeKey, AnalysisStatus.RUNNING, staleBefore);

        int prepared = 0;
        for (RequirementAnalysisJobItem item : failed) {
            Long versionId = projectService.currentVersion(item.getRequirement()).getId();
            prepared += itemRepository.resetFailed(
                    item.getId(),
                    jobId,
                    projectId,
                    scopeKey,
                    AnalysisStatus.FAILED,
                    AnalysisStatus.PENDING,
                    versionId);
        }
        for (RequirementAnalysisJobItem item : stale) {
            Long versionId = projectService.currentVersion(item.getRequirement()).getId();
            prepared += itemRepository.resetExpiredRunning(
                    item.getId(),
                    jobId,
                    projectId,
                    scopeKey,
                    AnalysisStatus.RUNNING,
                    AnalysisStatus.PENDING,
                    staleBefore,
                    versionId);
        }

        if (prepared == 0) {
            throw PortfolioException.conflict(
                    "Analysis job has no failed or expired running items to retry: " + jobId);
        }
        job.markPending();
        return prepared;
    }

    /**
     * Restores the aggregate job to PENDING only when prepared work exists and no
     * item in the exact tenant currently owns a RUNNING claim. This transactional
     * re-check prevents a completing worker from leaving an undispatched retry in
     * a misleading RUNNING job state.
     *
     * @return {@code true} when the job was reconciled to PENDING
     */
    @Transactional
    public boolean markPendingWhenOnlyPreparedItemsRemain(String jobId,
                                                          Long projectId,
                                                          String scopeKey) {
        String exactScope = requireScope(scopeKey);
        RequirementAnalysisJob job = requireJob(jobId, projectId, exactScope);
        List<RequirementAnalysisJobItem> items = itemRepository
                .findByJobIdAndProjectIdAndScopeKeyOrderByRequirementRequirementKeyAsc(
                        jobId, projectId, exactScope);
        boolean pending = items.stream()
                .anyMatch(item -> item.getStatus() == AnalysisStatus.PENDING);
        boolean running = items.stream()
                .anyMatch(item -> item.getStatus() == AnalysisStatus.RUNNING);
        if (pending && !running) {
            job.markPending();
            return true;
        }
        return false;
    }

    private RequirementAnalysisJob requireJob(String jobId,
                                              Long projectId,
                                              String scopeKey) {
        return jobRepository.findByIdAndProjectIdAndScopeKey(
                        jobId, projectId, requireScope(scopeKey))
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis job not found: " + jobId));
    }

    private static String requireScope(String scopeKey) {
        if (scopeKey == null || scopeKey.isBlank()) {
            throw PortfolioException.validation("Exact analysis tenant scope is required");
        }
        return scopeKey.strip();
    }
}
