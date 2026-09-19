package com.taxonomy.portfolio;

import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
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
        ProjectRequirement requirement = org.mockito.Mockito.mock(ProjectRequirement.class);
        RequirementAnalysisSnapshot snapshot =
                org.mockito.Mockito.mock(RequirementAnalysisSnapshot.class);
        String scopeKey = PortfolioScope.key(context.username(), context);
        when(requirementRepository.findByIdAndProjectIdAndScopeKey(
                7L, 41L, scopeKey)).thenReturn(Optional.of(requirement));
        when(snapshotRepository.findByIdAndProjectIdAndScopeKey(
                "snapshot-1", 41L, scopeKey)).thenReturn(Optional.of(snapshot));
        when(snapshot.getRequirementId()).thenReturn(7L);
        when(snapshot.getRequirementVersionId()).thenReturn(9L);
        when(requirement.getCurrentVersionId()).thenReturn(9L);
        when(requirement.getCurrentAnalysisSnapshotId()).thenReturn("snapshot-1");

        assertThat(service.selectCurrentSnapshot(
                41L, 7L, "snapshot-1", context.username(), context))
                .isEqualTo("snapshot-1");

        verify(requirement, never()).pointToAnalysis(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(requirementRepository, never()).save(requirement);
    }
}
