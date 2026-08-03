package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Claims pending analysis items in a dedicated short persistence transaction
 * and returns self-contained work payloads. External LLM execution starts only
 * after this method's independent transaction has committed.
 */
@Service
public class PortfolioAnalysisWorkQueue {

    public record WorkItem(
            Long itemId,
            Long projectId,
            Long requirementId,
            String requirementKey,
            Long requirementVersionId,
            int requirementVersionNumber,
            String requirementText) {
    }

    private final RequirementAnalysisJobRepository jobRepository;
    private final RequirementAnalysisJobItemRepository itemRepository;

    public PortfolioAnalysisWorkQueue(RequirementAnalysisJobRepository jobRepository,
                                      RequirementAnalysisJobItemRepository itemRepository) {
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
    }

    /**
     * Claims pending items with compare-and-set semantics and materializes their
     * work payloads before the dedicated transaction ends. Competing requests
     * can observe the same candidates, but only one can update a row from
     * PENDING to RUNNING.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<WorkItem> pending(String jobId, Long projectId) {
        jobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> PortfolioException.notFound("Analysis job not found: " + jobId));

        List<RequirementAnalysisJobItem> candidates = itemRepository
                .findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(jobId, AnalysisStatus.PENDING);
        if (candidates.isEmpty()) {
            return List.of();
        }

        Instant claimedAt = Instant.now();
        List<WorkItem> claimed = new ArrayList<>(candidates.size());
        for (RequirementAnalysisJobItem item : candidates) {
            int updated = itemRepository.claimPending(
                    item.getId(), AnalysisStatus.PENDING, AnalysisStatus.RUNNING, claimedAt);
            if (updated == 1) {
                claimed.add(toWorkItem(item));
            }
        }
        return List.copyOf(claimed);
    }

    private WorkItem toWorkItem(RequirementAnalysisJobItem item) {
        return new WorkItem(
                item.getId(),
                item.getJob().getProject().getId(),
                item.getRequirement().getId(),
                item.getRequirement().getRequirementKey(),
                item.getRequirementVersion().getId(),
                item.getRequirementVersion().getVersionNumber(),
                item.getRequirementVersion().getText());
    }
}
