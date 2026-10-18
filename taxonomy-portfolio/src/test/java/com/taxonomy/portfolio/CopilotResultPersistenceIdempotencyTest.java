package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.repository.AnalysisSnapshotIdentityRow;
import com.taxonomy.portfolio.repository.RequirementAnalysisSelectionRow;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.portfolio.service.CopilotResultPersistenceService;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotResultPersistenceIdempotencyTest {

    @Mock private ProjectRequirementRepository requirementRepository;
    @Mock private RequirementAnalysisSnapshotRepository snapshotRepository;

    @InjectMocks private CopilotResultPersistenceService service;

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");

    @Test
    void selectingTheAlreadyCurrentSnapshotDoesNotChurnRequirementState() {
        String scopeKey = PortfolioScope.key(context.username(), context);
        when(requirementRepository.findAnalysisSelectionForUpdate(7L, 41L, scopeKey))
                .thenReturn(Optional.of(new RequirementAnalysisSelectionRow(7L, 9L, "snapshot-1")));
        when(snapshotRepository.findIdentityByIdAndProjectIdAndScopeKey("snapshot-1", 41L, scopeKey))
                .thenReturn(Optional.of(new AnalysisSnapshotIdentityRow("snapshot-1", 7L, 9L)));

        assertThat(service.selectCurrentSnapshot(
                41L, 7L, "snapshot-1", context.username(), context))
                .isEqualTo("snapshot-1");

        verify(requirementRepository, never()).findByIdAndProjectIdAndScopeKeyForUpdate(7L, 41L, scopeKey);
        verify(requirementRepository, never()).save(org.mockito.ArgumentMatchers.any(ProjectRequirement.class));
    }
}
