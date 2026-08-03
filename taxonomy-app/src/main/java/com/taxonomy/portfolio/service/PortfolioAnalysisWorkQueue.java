package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Materializes and atomically claims all data needed for one analysis outside the persistence transaction. */
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
     * Claims pending items with compare-and-set semantics before returning their
     * detached work payloads. Competing requests can observe the same candidate
     * IDs, but only one request can update a given row from PENDING to RUNNING.
     */
    @Transactional
    public List<WorkItem> pending(String jobId, Long projectId) {
        jobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> PortfolioException.notFound("Analysis job not found: " + jobId));

        List<Long> candidateIds = itemRepository
                .findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(jobId, AnalysisStatus.PENDING)
                .stream()
                .map(RequirementAnalysisJobItem::getId)
                .toList();
        if (candidateIds.isEmpty()) {
            return List.of();
        }

        Instant claimedAt = Instant.now();
        List<WorkItem> claimed = new ArrayList<>(candidateIds.size());
        for (Long itemId : candidateIds) {
            int updated = itemRepository.claimPending(
                    itemId, AnalysisStatus.PENDING, AnalysisStatus.RUNNING, claimedAt);
            if (updated == 1) {
                RequirementAnalysisJobItem item = itemRepository.findById(itemId)
                        .orElseThrow(() -> PortfolioException.notFound(
                                "Claimed analysis job item not found: " + itemId));
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
