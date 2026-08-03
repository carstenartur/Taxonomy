package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Recovers failed items and expired worker claims without duplicating active work. */
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
     * Keeps already pending items dispatchable and resets failed items plus RUNNING
     * items whose claim is older than the supplied cutoff. Active claims remain
     * untouched. Every successful reset increments the attempt and binds the retry
     * to the requirement's current immutable text version; merely redispatching an
     * existing PENDING item does not increment it.
     *
     * @return number of pending or atomically reset items available for another dispatch
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
        RequirementAnalysisJob job = jobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis job not found: " + jobId));

        List<RequirementAnalysisJobItem> pending =
                itemRepository.findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
                        jobId, AnalysisStatus.PENDING);
        List<RequirementAnalysisJobItem> failed =
                itemRepository.findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
                        jobId, AnalysisStatus.FAILED);
        List<RequirementAnalysisJobItem> stale = itemRepository
                .findByJobIdAndStatusAndStartedAtBeforeOrderByRequirementRequirementKeyAsc(
                        jobId, AnalysisStatus.RUNNING, staleBefore);

        int prepared = pending.size();
        for (RequirementAnalysisJobItem item : failed) {
            ProjectRequirementVersion version =
                    projectService.currentVersion(item.getRequirement());
            prepared += itemRepository.resetFailed(
                    item.getId(),
                    AnalysisStatus.FAILED,
                    AnalysisStatus.PENDING,
                    version);
        }
        for (RequirementAnalysisJobItem item : stale) {
            ProjectRequirementVersion version =
                    projectService.currentVersion(item.getRequirement());
            prepared += itemRepository.resetExpiredRunning(
                    item.getId(),
                    AnalysisStatus.RUNNING,
                    AnalysisStatus.PENDING,
                    staleBefore,
                    version);
        }

        if (prepared == 0) {
            throw PortfolioException.conflict(
                    "Analysis job has no pending, failed or expired running items to retry: " + jobId);
        }
        job.markRunning(Instant.now());
        return prepared;
    }
}
