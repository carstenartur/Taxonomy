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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * cutoff. Active claims remain untouched. Every reset increments the attempt
     * and binds the retry to the requirement's current immutable text version.
     *
     * @return number of items prepared for another attempt
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

        Map<Long, RequirementAnalysisJobItem> retryable = new LinkedHashMap<>();
        failed.forEach(item -> retryable.put(item.getId(), item));
        stale.forEach(item -> retryable.put(item.getId(), item));
        if (retryable.isEmpty()) {
            throw PortfolioException.conflict(
                    "Analysis job has no failed or expired running items to retry: " + jobId);
        }

        for (RequirementAnalysisJobItem item : retryable.values()) {
            item.prepareRetry(projectService.currentVersion(item.getRequirement()));
        }
        job.markRunning(Instant.now());
        return retryable.size();
    }
}
