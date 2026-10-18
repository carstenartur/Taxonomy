package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.repository.AnalysisSnapshotIdentityRow;
import com.taxonomy.portfolio.repository.RequirementAnalysisSelectionRow;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/** Makes the selected pass current only after exact tenant and requirement validation. */
@Service
public class CopilotResultPersistenceService {

    private final ProjectRequirementRepository requirementRepository;
    private final RequirementAnalysisSnapshotRepository snapshotRepository;

    public CopilotResultPersistenceService(
            ProjectRequirementRepository requirementRepository,
            RequirementAnalysisSnapshotRepository snapshotRepository) {
        this.requirementRepository = requirementRepository;
        this.snapshotRepository = snapshotRepository;
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
