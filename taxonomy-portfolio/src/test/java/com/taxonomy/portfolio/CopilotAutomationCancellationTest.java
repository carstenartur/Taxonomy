package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobItemView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.AiCostPolicy;
import com.taxonomy.portfolio.model.AnalysisAutomationProfile;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.AiAutomationPolicy;
import com.taxonomy.portfolio.service.CopilotAutomationService;
import com.taxonomy.portfolio.service.CopilotCompletionService;
import com.taxonomy.portfolio.service.CopilotJobControlService;
import com.taxonomy.portfolio.service.CopilotOperationKey;
import com.taxonomy.portfolio.service.CopilotResultPersistenceService;
import com.taxonomy.portfolio.service.CopilotResultSelector;
import com.taxonomy.portfolio.service.PortfolioFingerprintService;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.portfolio.service.ProjectRequirementAnalysisService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotAutomationCancellationTest {

    @Mock private ProjectRequirementAnalysisService analysisService;
    @Mock private ProjectPortfolioService projectService;
    @Mock private PortfolioFingerprintService fingerprintService;
    @Mock private AiAutomationPolicy policy;
    @Mock private CopilotResultSelector resultSelector;
    @Mock private CopilotResultPersistenceService resultPersistenceService;
    @Mock private CopilotCompletionService completionService;
    @Mock private CopilotJobControlService jobControlService;
    @Mock private ExecutorService coordinator;

    @InjectMocks private CopilotAutomationService service;

    @Test
    void cancelledOperationKeepsSnapshotsArchivedWithoutPromotionOrEnrichment() {
        String operationId = "a".repeat(64);
        Long projectId = 41L;
        Long requirementId = 7L;
        String snapshotId = "snapshot-1";
        WorkspaceContext context = new WorkspaceContext(
                "architect", "ws-architect", "draft");
        CopilotOperationKey key = new CopilotOperationKey(
                false,
                operationId,
                1,
                1,
                AnalysisAutomationProfile.FULL,
                true,
                true);
        AnalysisJobItemView item = new AnalysisJobItemView(
                11L,
                requirementId,
                "REQ-001",
                9L,
                1,
                AnalysisStatus.SUCCESS,
                snapshotId,
                1,
                Instant.now(),
                Instant.now(),
                null);
        AnalysisJobView job = new AnalysisJobView(
                "job-1",
                projectId,
                AnalysisStatus.CANCELLED,
                key.value(),
                "CUSTOM_OPENAI",
                50,
                context.username(),
                context.workspaceId(),
                Instant.now(),
                Instant.now(),
                Instant.now(),
                1,
                1,
                0,
                0,
                null,
                List.of(item));
        RequirementView requirement = new RequirementView(
                requirementId,
                projectId,
                "REQ-001",
                "Secure communication",
                RequirementStatus.DRAFT,
                50,
                Criticality.MEDIUM,
                RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED,
                context.username(),
                9L,
                null,
                Instant.now(),
                Instant.now(),
                null);

        when(analysisService.listJobs(projectId, context.username(), context))
                .thenReturn(List.of(job));
        when(projectService.getRequirement(
                projectId, requirementId, context.username(), context))
                .thenReturn(requirement);
        when(policy.costPolicy()).thenReturn(AiCostPolicy.UNMETERED);

        CopilotOperationView result = service.getOperation(
                projectId, operationId, context.username(), context);

        assertThat(result.status()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(result.selectedSnapshotId()).isNull();
        verify(analysisService, never()).getSnapshot(
                projectId, snapshotId, context.username(), context);
        verifyNoInteractions(
                resultSelector,
                resultPersistenceService,
                completionService,
                coordinator);
    }
}
