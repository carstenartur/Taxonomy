package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
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
        ProjectRequirement requirement = requirementRepository
                .findByIdAndProjectIdAndScopeKey(requirementId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Requirement " + requirementId + " was not found in project " + projectId));
        RequirementAnalysisSnapshot snapshot = snapshotRepository
                .findByIdAndProjectIdAndScopeKey(snapshotId, projectId, scopeKey)
                .orElseThrow(() -> PortfolioException.notFound(
                        "Analysis snapshot not found: " + snapshotId));
        if (!Objects.equals(snapshot.getRequirementId(), requirementId)) {
            throw PortfolioException.validation(
                    "Selected snapshot does not belong to requirement " + requirementId);
        }
        if (!Objects.equals(snapshot.getRequirementVersionId(), requirement.getCurrentVersionId())) {
            throw PortfolioException.conflict(
                    "Selected snapshot is stale for the current requirement version");
        }
        requirement.pointToAnalysis(snapshotId, Instant.now());
        requirementRepository.save(requirement);
        return snapshotId;
    }
}
