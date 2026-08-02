package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Materializes all data needed for one analysis outside the persistence transaction. */
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

    @Transactional(readOnly = true)
    public List<WorkItem> pending(String jobId, Long projectId) {
        jobRepository.findByIdAndProjectId(jobId, projectId)
                .orElseThrow(() -> PortfolioException.notFound("Analysis job not found: " + jobId));
        return itemRepository.findByJobIdAndStatusOrderByRequirementRequirementKeyAsc(
                        jobId, AnalysisStatus.PENDING).stream()
                .map(this::toWorkItem)
                .toList();
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
