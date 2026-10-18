package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.repository.AnalysisSnapshotIdentityRow;
import com.taxonomy.portfolio.repository.RequirementAnalysisSelectionRow;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;

/** Makes the selected pass current only after exact tenant and requirement validation. */
@Service
public class CopilotResultPersistenceService {

    private final ProjectRequirementRepository requirementRepository;
    private final RequirementAnalysisSnapshotRepository snapshotRepository;
    private final RequirementAnalysisJobItemRepository itemRepository;

    public CopilotResultPersistenceService(
            ProjectRequirementRepository requirementRepository,
            RequirementAnalysisSnapshotRepository snapshotRepository,
            RequirementAnalysisJobItemRepository itemRepository) {
        this.requirementRepository = requirementRepository;
        this.snapshotRepository = snapshotRepository;
        this.itemRepository = itemRepository;
    }

    /** A stale status reader cannot promote or enrich a superseded pass generation. */
    @Transactional
    public String selectCurrentSnapshot(Long projectId, Long requirementId, String snapshotId,
            List<AnalysisJobView> expectedJobs, String username, WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        if (expectedJobs == null || expectedJobs.isEmpty()) return null;
        var expectedItems = expectedJobs.stream()
                .flatMap(job -> job.items().stream().map(item -> Map.entry(job, item)))
                .sorted(Comparator.comparing(entry -> entry.getValue().id())).toList();
        if (expectedItems.isEmpty()) return null;
        boolean selectedCurrentResult = false;
        for (var entry : expectedItems) {
            var job = entry.getKey(); var expected = entry.getValue();
            if (!Objects.equals(job.projectId(), projectId)
                    || !Objects.equals(expected.requirementId(), requirementId)
                    || !Set.of(AnalysisStatus.SUCCESS, AnalysisStatus.PARTIAL, AnalysisStatus.FAILED).contains(job.status()))
                return null;
            // Admission and worker persistence take this same row lock. Take all
            // item locks in ID order, then the requirement lock, as those paths do.
            var current = itemRepository.findPromotionClaimForUpdate(expected.id(), job.id(), projectId, scopeKey)
                    .orElseThrow(() -> PortfolioException.notFound("Analysis item not found: " + expected.id()));
            if (current.attempt() != expected.attempt() || current.status() != expected.status()
                    || itemRepository.findJobStatusForItem(expected.id(), job.id(), projectId, scopeKey).orElse(null) != job.status()
                    || !Objects.equals(current.requirementId(), requirementId)
                    || !Objects.equals(current.requirementVersionId(), expected.requirementVersionId())
                    || !Objects.equals(current.snapshotId(), expected.snapshotId())
                    || !Set.of(AnalysisStatus.SUCCESS, AnalysisStatus.PARTIAL, AnalysisStatus.FAILED).contains(current.status()))
                return null;
            selectedCurrentResult |= (current.status() == AnalysisStatus.SUCCESS || current.status() == AnalysisStatus.PARTIAL)
                    && Objects.equals(current.snapshotId(), snapshotId);
        }
        return selectedCurrentResult
                ? selectCurrentSnapshot(projectId, requirementId, snapshotId, username, context) : null;
    }

    @Transactional
    public String selectCurrentSnapshot(
            Long projectId,
            Long requirementId,
            String snapshotId,
            String username,
            WorkspaceContext context) {
        String scopeKey = PortfolioScope.key(username, context);
        // The coordinator and any status readers can finalize the same operation.
        // Serialize the version check and pointer update across transactions/nodes.
        RequirementAnalysisSelectionRow selected = requirementRepository
                .findAnalysisSelectionForUpdate(requirementId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
        AnalysisSnapshotIdentityRow snapshot = snapshotRepository
                .findIdentityByIdAndProjectIdAndScopeKey(snapshotId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis snapshot not found: " + snapshotId));
        if (!Objects.equals(snapshot.requirementId(), requirementId)) {
            throw PortfolioException.validation(
                    "Selected snapshot does not belong to requirement " + requirementId);
        }
        if (!Objects.equals(snapshot.requirementVersionId(), selected.currentVersionId())) {
            throw PortfolioException.conflict(
                    "Selected snapshot is stale for the current requirement version");
        }
        if (Objects.equals(selected.currentAnalysisSnapshotId(), snapshotId)) {
            return snapshotId;
        }
        // Actual pointer changes retain the managed-entity mutation and its optimistic version.
        // The scalar row lock above stays held until this transaction completes.
        ProjectRequirement requirement = requirementRepository
                .findByIdAndProjectIdAndScopeKeyForUpdate(requirementId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
        requirement.pointToAnalysis(snapshotId, Instant.now());
        requirementRepository.save(requirement);
        return snapshotId;
    }
}
