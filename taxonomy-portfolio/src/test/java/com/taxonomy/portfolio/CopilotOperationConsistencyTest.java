package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobItemView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotSummary;
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
import com.taxonomy.portfolio.service.PortfolioException;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotOperationConsistencyTest {

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

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");

    @Test
    void cancellationRejectsRequirementDriftBeforeTouchingAnyJob() {
        String operationId = "a".repeat(64);
        AnalysisJobView passOne = job(
                "job-1", operationId, 1, 2, 7L, AnalysisStatus.RUNNING);
        AnalysisJobView passTwo = job(
                "job-2", operationId, 2, 2, 8L, AnalysisStatus.RUNNING);
        when(analysisService.listJobs(41L, context.username(), context))
                .thenReturn(List.of(passOne, passTwo));

        assertThatThrownBy(() -> service.cancelOperation(
                41L, operationId, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("metadata is inconsistent");

        verifyNoInteractions(jobControlService, coordinator);
    }

    @Test
    void duplicatePassNumbersAreRejectedBeforeResumeOrPromotion() {
        String operationId = "b".repeat(64);
        AnalysisJobView first = job(
                "job-1", operationId, 1, 2, 7L, AnalysisStatus.SUCCESS);
        AnalysisJobView duplicate = job(
                "job-2", operationId, 1, 2, 7L, AnalysisStatus.SUCCESS);
        when(analysisService.listJobs(41L, context.username(), context))
                .thenReturn(List.of(first, duplicate));

        assertThatThrownBy(() -> service.getOperation(
                41L, operationId, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("duplicate pass 1");

        verifyNoInteractions(
                resultSelector, resultPersistenceService, completionService, coordinator);
    }

    @Test
    void latestOperationUsesTheFirstPassStartInsteadOfADeferredLaterPass() {
        String olderOperation = "c".repeat(64);
        String newerOperation = "d".repeat(64);
        Instant base = Instant.parse("2026-08-18T10:00:00Z");
        AnalysisJobView olderPassOne = job(
                "old-1", olderOperation, 1, 2, 7L,
                AnalysisStatus.CANCELLED, base);
        AnalysisJobView newerPassOne = job(
                "new-1", newerOperation, 1, 2, 7L,
                AnalysisStatus.CANCELLED, base.plusSeconds(60));
        AnalysisJobView newerPassTwo = job(
                "new-2", newerOperation, 2, 2, 7L,
                AnalysisStatus.CANCELLED, base.plusSeconds(120));
        AnalysisJobView olderDeferredPassTwo = job(
                "old-2", olderOperation, 2, 2, 7L,
                AnalysisStatus.CANCELLED, base.plusSeconds(180));
        List<AnalysisJobView> jobs = List.of(
                olderPassOne, newerPassOne, newerPassTwo, olderDeferredPassTwo);
        RequirementView requirement = mock(RequirementView.class);
        when(analysisService.listJobs(41L, context.username(), context))
                .thenReturn(jobs);
        when(projectService.getRequirement(41L, 7L, context.username(), context))
                .thenReturn(requirement);
        when(policy.costPolicy()).thenReturn(AiCostPolicy.METERED);

        var latest = service.latestOperation(
                41L, 7L, context.username(), context);

        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().operationId()).isEqualTo(newerOperation);
        verifyNoInteractions(resultSelector, resultPersistenceService, completionService, coordinator);
    }

    @Test
    void saturatedCoordinatorLeavesThePersistedJobRecoverable() {
        CopilotRunRequest request = new CopilotRunRequest(
                null, "CUSTOM_OPENAI", 50, AnalysisAutomationProfile.FULL,
                1, false, true, true);
        AiAutomationPolicy.RunSettings settings = new AiAutomationPolicy.RunSettings(
                false,
                AnalysisAutomationProfile.FULL,
                "CUSTOM_OPENAI",
                mock(com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor.class),
                50,
                1,
                true,
                true,
                false);
        RequirementVersionView version = new RequirementVersionView(
                9L,
                1,
                "Need secure communication",
                "content-hash",
                null,
                context.username(),
                Instant.now(),
                null);
        RequirementView requirement = new RequirementView(
                7L,
                41L,
                "REQ-001",
                "Secure communication",
                RequirementStatus.DRAFT,
                50,
                Criticality.HIGH,
                RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED,
                context.username(),
                version.id(),
                null,
                Instant.now(),
                Instant.now(),
                version);

        when(policy.manual(request)).thenReturn(settings);
        when(projectService.getRequirement(41L, 7L, context.username(), context))
                .thenReturn(requirement);
        when(fingerprintService.taxonomyFingerprint()).thenReturn("taxonomy-fingerprint");
        when(fingerprintService.promptFingerprint()).thenReturn("prompt-fingerprint");
        doThrow(new RejectedExecutionException("queue full"))
                .when(coordinator).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.enqueueManual(
                41L, 7L, request, context.username(), context))
                .isInstanceOfSatisfying(PortfolioException.class, failure -> {
                    assertThat(failure.getKind()).isEqualTo(PortfolioException.Kind.UNAVAILABLE);
                    assertThat(failure.getMessage()).contains("persisted operation can be resumed");
                });

        verify(analysisService).enqueueRequirement(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("CUSTOM_OPENAI"),
                org.mockito.ArgumentMatchers.eq(50),
                org.mockito.ArgumentMatchers.startsWith("copilot:v1:"),
                org.mockito.ArgumentMatchers.eq(context.username()),
                org.mockito.ArgumentMatchers.eq(context));
        verify(analysisService, never()).getSnapshot(any(), any(), any(), any());
    }

    @Test
    void cancellationBetweenPassesPersistsATerminalMarker() {
        String operationId = "e".repeat(64);
        AnalysisJobView completedPass = job(
                "job-1", operationId, 1, 2, 7L, AnalysisStatus.SUCCESS);
        AnalysisJobView pendingMarker = job(
                "job-2", operationId, 2, 2, 7L, AnalysisStatus.PENDING);
        AnalysisJobView cancelledMarker = job(
                "job-2", operationId, 2, 2, 7L, AnalysisStatus.CANCELLED);
        RequirementView requirement = mock(RequirementView.class);

        when(analysisService.listJobs(41L, context.username(), context))
                .thenReturn(
                        List.of(completedPass),
                        List.of(completedPass),
                        List.of(completedPass, cancelledMarker));
        when(analysisService.enqueueRequirement(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("CUSTOM_OPENAI"),
                org.mockito.ArgumentMatchers.eq(50),
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.eq(context.username()),
                org.mockito.ArgumentMatchers.eq(context)))
                .thenReturn(pendingMarker);
        when(projectService.getRequirement(
                41L, 7L, context.username(), context)).thenReturn(requirement);
        when(policy.costPolicy()).thenReturn(AiCostPolicy.METERED);

        var result = service.cancelOperation(
                41L, operationId, context.username(), context);

        assertThat(result.status()).isEqualTo(AnalysisStatus.CANCELLED);
        assertThat(result.completedPasses()).isEqualTo(2);
        verify(analysisService).enqueueRequirement(
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq("CUSTOM_OPENAI"),
                org.mockito.ArgumentMatchers.eq(50),
                org.mockito.ArgumentMatchers.any(String.class),
                org.mockito.ArgumentMatchers.eq(context.username()),
                org.mockito.ArgumentMatchers.eq(context));
        verify(jobControlService).cancel(
                "job-2", 41L, context.username(), context);
        verifyNoInteractions(
                resultSelector, resultPersistenceService, completionService, coordinator);
    }

    @Test
    void cancellationDominatesAndStopsLateRunningPasses() {
        String operationId = "f".repeat(64);
        AnalysisJobView cancelledPass = job(
                "job-1", operationId, 1, 2, 7L, AnalysisStatus.CANCELLED);
        AnalysisJobView runningPass = job(
                "job-2", operationId, 2, 2, 7L, AnalysisStatus.RUNNING);
        AnalysisJobView cancelledLatePass = job(
                "job-2", operationId, 2, 2, 7L, AnalysisStatus.CANCELLED);
        RequirementView requirement = mock(RequirementView.class);

        when(analysisService.listJobs(41L, context.username(), context))
                .thenReturn(
                        List.of(cancelledPass, runningPass),
                        List.of(cancelledPass, runningPass),
                        List.of(cancelledPass, cancelledLatePass));
        when(projectService.getRequirement(
                41L, 7L, context.username(), context)).thenReturn(requirement);
        when(policy.costPolicy()).thenReturn(AiCostPolicy.METERED);

        var result = service.cancelOperation(
                41L, operationId, context.username(), context);

        assertThat(result.status()).isEqualTo(AnalysisStatus.CANCELLED);
        verify(jobControlService).cancel(
                "job-2", 41L, context.username(), context);
        verify(analysisService, never()).enqueueRequirement(
                any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(
                resultSelector, resultPersistenceService, completionService, coordinator);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void terminalStatusFinalizesTheSameJobsItExposesExactlyOnce(boolean initiallyRunning) {
        String operationId = "9".repeat(64);
        var first = job("job-1", operationId, 1, 2, 7L, AnalysisStatus.SUCCESS);
        var running = job("job-2", operationId, 2, 2, 7L, AnalysisStatus.RUNNING);
        var second = job("job-2", operationId, 2, 2, 7L, AnalysisStatus.SUCCESS);
        arrangeWinningSnapshot();
        when(analysisService.listJobs(41L, context.username(), context))
                .thenReturn(List.of(first, initiallyRunning ? running : second), List.of(first, second));

        var result = service.getOperation(41L, operationId, context.username(), context);

        assertThat(result.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(result.selectedSnapshotId()).as("Expose the promoted winner, not the last completed pass").isEqualTo("snapshot-1");
        verify(resultPersistenceService).selectCurrentSnapshot(41L, 7L, "snapshot-1", context.username(), context);
        verify(completionService).enrich(41L, context.username(), context, true, true);
    }

    @Test
    void fastCompletedEnqueuePromotesWinningSnapshotBeforeSuccess() {
        var requirement = arrangeWinningSnapshot();
        var version = mock(RequirementVersionView.class);
        when(requirement.id()).thenReturn(7L);
        when(requirement.currentVersion()).thenReturn(version);
        when(version.id()).thenReturn(9L);
        when(version.contentHash()).thenReturn("content-hash");
        when(fingerprintService.taxonomyFingerprint()).thenReturn("taxonomy-fingerprint");
        when(fingerprintService.promptFingerprint()).thenReturn("prompt-fingerprint");
        var request = new CopilotRunRequest(null, "CUSTOM_OPENAI", 50, AnalysisAutomationProfile.FULL, 2, false, true, true);
        when(policy.manual(request)).thenReturn(new AiAutomationPolicy.RunSettings(false, AnalysisAutomationProfile.FULL,
                "CUSTOM_OPENAI", mock(com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor.class), 50, 2, true, true, false));
        var operationId = new AtomicReference<String>();
        when(analysisService.enqueueRequirement(eq(41L), eq(7L), eq("CUSTOM_OPENAI"), eq(50), anyString(), eq(context.username()), eq(context)))
                .thenAnswer(invocation -> {
                    operationId.set(CopilotOperationKey.parse(invocation.getArgument(4)).orElseThrow().operationId());
                    return job("job-1", operationId.get(), 1, 2, 7L, AnalysisStatus.SUCCESS);
                });
        when(analysisService.listJobs(41L, context.username(), context)).thenAnswer(invocation -> List.of(
                job("job-1", operationId.get(), 1, 2, 7L, AnalysisStatus.SUCCESS),
                job("job-2", operationId.get(), 2, 2, 7L, AnalysisStatus.SUCCESS)));

        var result = service.enqueueManual(41L, 7L, request, context.username(), context);

        assertThat(result.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(result.selectedSnapshotId()).as("Fast completion still promotes the selected winner before returning").isEqualTo("snapshot-1");
        verify(resultPersistenceService).selectCurrentSnapshot(41L, 7L, "snapshot-1", context.username(), context);
        verify(completionService).enrich(41L, context.username(), context, true, true);
    }

    /** The persistence boundary starts on pass 2 and changes only when promotion is invoked. */
    private RequirementView arrangeWinningSnapshot() {
        var currentSnapshot = new AtomicReference<>("snapshot-2");
        var requirement = mock(RequirementView.class);
        when(projectService.getRequirement(41L, 7L, context.username(), context)).thenReturn(requirement);
        when(requirement.currentAnalysisSnapshotId()).thenAnswer(invocation -> currentSnapshot.get());
        when(policy.costPolicy()).thenReturn(AiCostPolicy.METERED);
        var winner = mock(SnapshotDetail.class);
        var summary = mock(SnapshotSummary.class);
        when(winner.summary()).thenReturn(summary);
        when(summary.id()).thenReturn("snapshot-1");
        when(analysisService.getSnapshot(41L, "snapshot-1", context.username(), context)).thenReturn(winner);
        when(analysisService.getSnapshot(41L, "snapshot-2", context.username(), context)).thenReturn(mock(SnapshotDetail.class));
        when(resultSelector.select(anyList())).thenReturn(Optional.of(winner));
        when(resultPersistenceService.selectCurrentSnapshot(41L, 7L, "snapshot-1", context.username(), context)).thenAnswer(invocation -> {
            currentSnapshot.set("snapshot-1");
            return "snapshot-1";
        });
        return requirement;
    }

    private static AnalysisJobView job(
            String jobId,
            String operationId,
            int pass,
            int totalPasses,
            Long requirementId,
            AnalysisStatus status) {
        return job(
                jobId,
                operationId,
                pass,
                totalPasses,
                requirementId,
                status,
                Instant.now());
    }

    private static AnalysisJobView job(
            String jobId,
            String operationId,
            int pass,
            int totalPasses,
            Long requirementId,
            AnalysisStatus status,
            Instant createdAt) {
        CopilotOperationKey key = new CopilotOperationKey(
                false,
                operationId,
                pass,
                totalPasses,
                AnalysisAutomationProfile.FULL,
                true,
                true);
        AnalysisJobItemView item = new AnalysisJobItemView(
                10L + pass,
                requirementId,
                "REQ-" + requirementId,
                90L + pass,
                1,
                status,
                status == AnalysisStatus.SUCCESS ? "snapshot-" + pass : null,
                1,
                createdAt,
                status == AnalysisStatus.RUNNING ? null : createdAt,
                null);
        return new AnalysisJobView(
                jobId,
                41L,
                status,
                key.value(),
                "CUSTOM_OPENAI",
                50,
                "architect",
                "ws-architect",
                createdAt,
                createdAt,
                status == AnalysisStatus.RUNNING ? null : createdAt,
                1,
                status == AnalysisStatus.SUCCESS ? 1 : 0,
                0,
                0,
                null,
                List.of(item));
    }
}
