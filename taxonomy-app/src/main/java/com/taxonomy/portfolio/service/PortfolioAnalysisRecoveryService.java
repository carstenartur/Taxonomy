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
     * Resets failed items and RUNNING items whose claim is older than the supplied
     * cutoff. Active claims remain untouched. Every successful compare-and-set
     * increments the attempt and binds the retry to the requirement's current
     * immutable text version. The job remains PENDING until a worker actually
     * claims its prepared items, so a lost or rejected dispatch can be retried.
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
        RequirementAnalysisJob job = jobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis job not found: " + jobId));

        List<RequirementAnalysisJobItem> failed =
                itemRepository.findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
                        jobId, AnalysisStatus.FAILED);
        List<RequirementAnalysisJobItem> stale = itemRepository
                .findByJobIdAndStatusAndStartedAtBeforeOrderByRequirementRequirementKeyAsc(
                        jobId, AnalysisStatus.RUNNING, staleBefore);

        int prepared = 0;
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
                    "Analysis job has no failed or expired running items to retry: " + jobId);
        }
        job.markPending();
        return prepared;
    }
}
