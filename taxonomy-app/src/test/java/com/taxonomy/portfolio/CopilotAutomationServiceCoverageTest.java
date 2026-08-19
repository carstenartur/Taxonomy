package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CopilotAutomationServiceCoverageTest {

    private static final Long PROJECT_ID = 41L;
    private static final Long REQUIREMENT_ID = 7L;

    private final WorkspaceContext context = new WorkspaceContext(
            "architect", "ws-architect", "draft");

    @Test
    void statusDelegatesAndDisabledAutopilotDoesNotPersistAnything() {
        Fixture fixture = new Fixture();
        AiAutomationStatus status = mock(AiAutomationStatus.class);
        when(fixture.policy.status()).thenReturn(status);
        when(fixture.policy.autopilotReady()).thenReturn(false);

        assertThat(fixture.service.status()).isSameAs(status);
        assertThat(fixture.service.tryAutopilot(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context)).isEmpty();

        verifyNoInteractions(
                fixture.analysisService,
                fixture.projectService,
                fixture.coordinator);
    }

    @Test
    void enqueueRejectsRequirementWithoutImmutableCurrentVersion() {
        Fixture fixture = new Fixture();
        CopilotRunRequest request = request();
        when(fixture.policy.manual(request)).thenReturn(settings(false, 1, true, true));
        when(fixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement(null, null));

        assertThatThrownBy(() -> fixture.service.enqueueManual(
                PROJECT_ID, REQUIREMENT_ID, request, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("immutable current version");

        verifyNoInteractions(fixture.analysisService, fixture.coordinator);
    }

    @Test
    void readyAutopilotPersistsOnePassAndExposesRecoverablePendingState() {
        Fixture fixture = new Fixture();
        RequirementView requirement = requirement(version(), null);
        AtomicReference<AnalysisJobView> persisted = new AtomicReference<>();
        when(fixture.policy.autopilotReady()).thenReturn(true);
        when(fixture.policy.autopilot()).thenReturn(settings(true, 1, true, true));
        when(fixture.policy.costPolicy()).thenReturn(AiCostPolicy.UNMETERED);
        when(fixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement);
        fingerprints(fixture);
        when(fixture.analysisService.enqueueRequirement(
                eq(PROJECT_ID), eq(REQUIREMENT_ID), eq("CUSTOM_OPENAI"), eq(50),
                anyString(), eq(context.username()), eq(context)))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(4);
                    AnalysisJobView job = jobWithKey(
                            "job-1", key, AnalysisStatus.PENDING, null, Instant.now());
                    persisted.set(job);
                    return job;
                });
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context))
                .thenAnswer(ignored -> persisted.get() == null
                        ? List.of() : List.of(persisted.get()));

        var result = fixture.service.tryAutopilot(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().autopilot()).isTrue();
        assertThat(result.orElseThrow().status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(result.orElseThrow().automaticSteps()).hasSize(8);
        verify(fixture.coordinator).execute(any(Runnable.class));
    }

    @Test
    void latestOperationIgnoresUnrelatedAndMalformedJobsAndSelectsNewestMatch() {
        Fixture fixture = new Fixture();
        String oldOperation = "a".repeat(64);
        String newOperation = "b".repeat(64);
        Instant now = Instant.now();
        AnalysisJobView nullItems = rawJob(
                "null-items", "not-copilot", AnalysisStatus.PENDING, null, now.minusSeconds(4));
        AnalysisJobView malformed = rawJob(
                "malformed", "not-copilot", AnalysisStatus.PENDING,
                List.of(item(REQUIREMENT_ID, AnalysisStatus.PENDING, null)),
                now.minusSeconds(3));
        AnalysisJobView wrongRequirement = job(
                "wrong", "c".repeat(64), 1, 1, 8L,
                AnalysisStatus.CANCELLED, null, now.minusSeconds(2));
        AnalysisJobView old = job(
                "old", oldOperation, 1, 1, REQUIREMENT_ID,
                AnalysisStatus.CANCELLED, null, now.minusSeconds(1));
        AnalysisJobView newest = job(
                "new", newOperation, 1, 1, REQUIREMENT_ID,
                AnalysisStatus.CANCELLED, null, now);
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context))
                .thenReturn(List.of(nullItems, malformed, wrongRequirement, old, newest));
        when(fixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement(version(), null));
        when(fixture.policy.costPolicy()).thenReturn(AiCostPolicy.METERED);

        var latest = fixture.service.latestOperation(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context);

        assertThat(latest).isPresent();
        assertThat(latest.orElseThrow().operationId()).isEqualTo(newOperation);
        assertThat(latest.orElseThrow().status()).isEqualTo(AnalysisStatus.CANCELLED);
        verifyNoInteractions(fixture.coordinator);
    }

    @Test
    void cancellationTouchesOnlyActivePassesAndUsesRefreshedPersistedState() {
        Fixture fixture = new Fixture();
        String operation = "d".repeat(64);
        Instant now = Instant.now();
        AnalysisJobView running = job(
                "job-1", operation, 1, 2, REQUIREMENT_ID,
                AnalysisStatus.RUNNING, null, now);
        AnalysisJobView success = job(
                "job-2", operation, 2, 2, REQUIREMENT_ID,
                AnalysisStatus.SUCCESS, "snapshot-2", now.plusMillis(1));
        AnalysisJobView cancelled = job(
                "job-1", operation, 1, 2, REQUIREMENT_ID,
                AnalysisStatus.CANCELLED, null, now.plusMillis(2));
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context))
                .thenReturn(List.of(running, success), List.of(cancelled, success));
        when(fixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement(version(), null));
        when(fixture.policy.costPolicy()).thenReturn(AiCostPolicy.UNMETERED);

        var result = fixture.service.cancelOperation(
                PROJECT_ID, operation, context.username(), context);

        assertThat(result.status()).isEqualTo(AnalysisStatus.CANCELLED);
        verify(fixture.jobControlService).cancel(
                "job-1", PROJECT_ID, context.username(), context);
        verify(fixture.jobControlService, never()).cancel(
                "job-2", PROJECT_ID, context.username(), context);
        verifyNoInteractions(fixture.coordinator);
    }

    @Test
    void missingOperationAndMalformedItemsFailClosedWithoutDisclosure() {
        Fixture fixture = new Fixture();
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context)).thenReturn(List.of());

        assertThatThrownBy(() -> fixture.service.getOperation(
                PROJECT_ID, "missing", context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> fixture.service.cancelOperation(
                PROJECT_ID, "missing", context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("not found");

        String operation = "e".repeat(64);
        CopilotOperationKey key = new CopilotOperationKey(
                false, operation, 1, 1, AnalysisAutomationProfile.FULL, false, false);
        AnalysisJobView noItem = rawJob(
                "job-empty", key.value(), AnalysisStatus.SUCCESS, List.of(), Instant.now());
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context)).thenReturn(List.of(noItem));
        assertThatThrownBy(() -> fixture.service.getOperation(
                PROJECT_ID, operation, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("exactly one requirement item");

        AnalysisJobItemView missingIdentity = new AnalysisJobItemView(
                11L, null, null, 9L, 1, AnalysisStatus.SUCCESS,
                null, 1, Instant.now(), Instant.now(), null);
        AnalysisJobView nullRequirement = rawJob(
                "job-null", key.value(), AnalysisStatus.SUCCESS,
                List.of(missingIdentity), Instant.now());
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context)).thenReturn(List.of(nullRequirement));
        assertThatThrownBy(() -> fixture.service.getOperation(
                PROJECT_ID, operation, context.username(), context))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("no requirement identity");
    }

    @Test
    void terminalSuccessPromotesStrongestSnapshotAndRunsReviewOnlyEnrichment() {
        Fixture fixture = new Fixture();
        String operation = "f".repeat(64);
        AnalysisJobView success = job(
                "job-1", operation, 1, 1, REQUIREMENT_ID,
                AnalysisStatus.SUCCESS, "snapshot-1", Instant.now());
        SnapshotDetail best = mock(SnapshotDetail.class);
        SnapshotSummary summary = mock(SnapshotSummary.class);
        when(summary.id()).thenReturn("snapshot-1");
        when(best.summary()).thenReturn(summary);
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context)).thenReturn(List.of(success));
        when(fixture.analysisService.getSnapshot(
                PROJECT_ID, "snapshot-1", context.username(), context)).thenReturn(best);
        when(fixture.resultSelector.select(anyList())).thenReturn(Optional.of(best));
        when(fixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement(version(), "snapshot-1"));
        when(fixture.policy.costPolicy()).thenReturn(AiCostPolicy.UNMETERED);

        var result = fixture.service.getOperation(
                PROJECT_ID, operation, context.username(), context);

        assertThat(result.status()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(result.selectedSnapshotId()).isEqualTo("snapshot-1");
        assertThat(result.message()).contains("complete", "human review");
        verify(fixture.resultPersistenceService).selectCurrentSnapshot(
                PROJECT_ID, REQUIREMENT_ID, "snapshot-1", context.username(), context);
        verify(fixture.completionService).enrich(
                PROJECT_ID, context.username(), context, true, true);
        verifyNoInteractions(fixture.coordinator);
    }

    @Test
    void terminalPartialAndFailureAreRenderedWithoutInventingPromotion() {
        for (AnalysisStatus status : List.of(AnalysisStatus.PARTIAL, AnalysisStatus.FAILED)) {
            Fixture fixture = new Fixture();
            String operation = (status == AnalysisStatus.PARTIAL ? "1" : "2").repeat(64);
            AnalysisJobView job = job(
                    "job-1", operation, 1, 1, REQUIREMENT_ID,
                    status, null, Instant.now());
            when(fixture.analysisService.listJobs(
                    PROJECT_ID, context.username(), context)).thenReturn(List.of(job));
            when(fixture.resultSelector.select(anyList())).thenReturn(Optional.empty());
            when(fixture.projectService.getRequirement(
                    PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                    .thenReturn(requirement(version(), null));
            when(fixture.policy.costPolicy()).thenReturn(AiCostPolicy.METERED);

            var result = fixture.service.getOperation(
                    PROJECT_ID, operation, context.username(), context);

            assertThat(result.status()).isEqualTo(status);
            assertThat(result.message()).isNotBlank();
            verifyNoInteractions(
                    fixture.resultPersistenceService,
                    fixture.completionService,
                    fixture.coordinator);
        }
    }

    @Test
    void runningAndIncompleteSuccessfulOperationsRemainRecoverable() {
        Fixture runningFixture = new Fixture();
        String runningOperation = "3".repeat(64);
        AnalysisJobView running = job(
                "job-1", runningOperation, 1, 1, REQUIREMENT_ID,
                AnalysisStatus.RUNNING, null, Instant.now());
        when(runningFixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context)).thenReturn(List.of(running));
        when(runningFixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement(version(), null));
        when(runningFixture.policy.costPolicy()).thenReturn(AiCostPolicy.METERED);

        var runningView = runningFixture.service.getOperation(
                PROJECT_ID, runningOperation, context.username(), context);
        assertThat(runningView.status()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(runningView.message()).contains("analyzing");
        verify(runningFixture.coordinator).execute(any(Runnable.class));

        Fixture incompleteFixture = new Fixture();
        String incompleteOperation = "4".repeat(64);
        AnalysisJobView incomplete = job(
                "job-1", incompleteOperation, 1, 2, REQUIREMENT_ID,
                AnalysisStatus.SUCCESS, null, Instant.now());
        when(incompleteFixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context)).thenReturn(List.of(incomplete));
        when(incompleteFixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement(version(), null));
        when(incompleteFixture.policy.costPolicy()).thenReturn(AiCostPolicy.METERED);

        var incompleteView = incompleteFixture.service.getOperation(
                PROJECT_ID, incompleteOperation, context.username(), context);
        assertThat(incompleteView.status()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(incompleteView.message()).contains("queued", "1/2");
        verify(incompleteFixture.coordinator).execute(any(Runnable.class));
    }

    @Test
    void queuedCoordinatorExecutesPersistedPassAndObservesTerminalJob() {
        Fixture fixture = new Fixture();
        CopilotRunRequest request = request();
        RequirementView requirement = requirement(version(), null);
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        AtomicBoolean terminalVisible = new AtomicBoolean(false);
        when(fixture.policy.manual(request)).thenReturn(settings(false, 1, false, false));
        when(fixture.policy.costPolicy()).thenReturn(AiCostPolicy.METERED);
        when(fixture.policy.maximumRuntime()).thenReturn(Duration.ofSeconds(5));
        when(fixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement);
        fingerprints(fixture);
        when(fixture.analysisService.enqueueRequirement(
                eq(PROJECT_ID), eq(REQUIREMENT_ID), eq("CUSTOM_OPENAI"), eq(50),
                anyString(), eq(context.username()), eq(context)))
                .thenAnswer(invocation -> {
                    key.set(invocation.getArgument(4));
                    return jobWithKey(
                            "job-1", key.get(), AnalysisStatus.PENDING, null, Instant.now());
                });
        when(fixture.analysisService.getJob(
                "job-1", PROJECT_ID, context.username(), context))
                .thenAnswer(ignored -> jobWithKey(
                        "job-1", key.get(), AnalysisStatus.SUCCESS, null, Instant.now()));
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context))
                .thenAnswer(ignored -> key.get() == null ? List.of() : List.of(jobWithKey(
                        "job-1",
                        key.get(),
                        terminalVisible.get() ? AnalysisStatus.SUCCESS : AnalysisStatus.PENDING,
                        null,
                        Instant.now())));
        when(fixture.resultSelector.select(anyList())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            Runnable original = invocation.getArgument(0);
            scheduled.set(() -> {
                terminalVisible.set(true);
                original.run();
            });
            return null;
        }).when(fixture.coordinator).execute(any(Runnable.class));

        var initial = fixture.service.enqueueManual(
                PROJECT_ID, REQUIREMENT_ID, request, context.username(), context);
        assertThat(initial.status()).isEqualTo(AnalysisStatus.PENDING);

        scheduled.get().run();
        var completed = fixture.service.getOperation(
                PROJECT_ID, initial.operationId(), context.username(), context);

        assertThat(completed.status()).isEqualTo(AnalysisStatus.SUCCESS);
        verify(fixture.analysisService, times(2)).enqueueRequirement(
                eq(PROJECT_ID), eq(REQUIREMENT_ID), eq("CUSTOM_OPENAI"), eq(50),
                anyString(), eq(context.username()), eq(context));
        verify(fixture.analysisService).getJob(
                "job-1", PROJECT_ID, context.username(), context);
    }

    @Test
    void coordinatorTimeoutAndInterruptionLeavePersistedWorkRecoverable() {
        Fixture timeout = fixtureWithCapturedCoordinator();
        CopilotRunRequest request = request();
        when(timeout.policy.manual(request)).thenReturn(settings(false, 1, false, false));
        when(timeout.policy.costPolicy()).thenReturn(AiCostPolicy.METERED);
        when(timeout.policy.maximumRuntime()).thenReturn(Duration.ZERO);
        preparePendingEnqueue(timeout, request);

        var timedOut = timeout.service.enqueueManual(
                PROJECT_ID, REQUIREMENT_ID, request, context.username(), context);
        captured(timeout).run();
        assertThat(timedOut.status()).isEqualTo(AnalysisStatus.PENDING);
        verify(timeout.analysisService, never()).getJob(
                anyString(), eq(PROJECT_ID), eq(context.username()), eq(context));

        Fixture interrupted = fixtureWithCapturedCoordinator();
        when(interrupted.policy.manual(request)).thenReturn(settings(false, 1, false, false));
        when(interrupted.policy.costPolicy()).thenReturn(AiCostPolicy.METERED);
        when(interrupted.policy.maximumRuntime()).thenReturn(Duration.ofSeconds(5));
        preparePendingEnqueue(interrupted, request);
        when(interrupted.analysisService.getJob(
                "job-1", PROJECT_ID, context.username(), context))
                .thenAnswer(ignored -> jobWithKey(
                        "job-1",
                        interrupted.persistedKey.get(),
                        AnalysisStatus.RUNNING,
                        null,
                        Instant.now()));

        interrupted.service.enqueueManual(
                PROJECT_ID, REQUIREMENT_ID, request, context.username(), context);
        Thread.currentThread().interrupt();
        try {
            captured(interrupted).run();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private Fixture fixtureWithCapturedCoordinator() {
        Fixture fixture = new Fixture();
        doAnswer(invocation -> {
            fixture.scheduled.set(invocation.getArgument(0));
            return null;
        }).when(fixture.coordinator).execute(any(Runnable.class));
        return fixture;
    }

    private Runnable captured(Fixture fixture) {
        assertThat(fixture.scheduled.get()).isNotNull();
        return fixture.scheduled.get();
    }

    private void preparePendingEnqueue(Fixture fixture, CopilotRunRequest request) {
        when(fixture.projectService.getRequirement(
                PROJECT_ID, REQUIREMENT_ID, context.username(), context))
                .thenReturn(requirement(version(), null));
        fingerprints(fixture);
        when(fixture.analysisService.enqueueRequirement(
                eq(PROJECT_ID), eq(REQUIREMENT_ID), eq("CUSTOM_OPENAI"), eq(50),
                anyString(), eq(context.username()), eq(context)))
                .thenAnswer(invocation -> {
                    fixture.persistedKey.set(invocation.getArgument(4));
                    return jobWithKey(
                            "job-1",
                            fixture.persistedKey.get(),
                            AnalysisStatus.PENDING,
                            null,
                            Instant.now());
                });
        when(fixture.analysisService.listJobs(
                PROJECT_ID, context.username(), context))
                .thenAnswer(ignored -> fixture.persistedKey.get() == null
                        ? List.of() : List.of(jobWithKey(
                                "job-1",
                                fixture.persistedKey.get(),
                                AnalysisStatus.PENDING,
                                null,
                                Instant.now())));
    }

    private void fingerprints(Fixture fixture) {
        when(fixture.fingerprintService.taxonomyFingerprint()).thenReturn("taxonomy-fingerprint");
        when(fixture.fingerprintService.promptFingerprint()).thenReturn("prompt-fingerprint");
    }

    private static CopilotRunRequest request() {
        return new CopilotRunRequest(
                "CUSTOM_OPENAI",
                50,
                AnalysisAutomationProfile.FULL,
                1,
                false,
                true,
                true);
    }

    private static AiAutomationPolicy.RunSettings settings(
            boolean autopilot,
            int passes,
            boolean solutions,
            boolean products) {
        return new AiAutomationPolicy.RunSettings(
                autopilot,
                AnalysisAutomationProfile.FULL,
                "CUSTOM_OPENAI",
                50,
                passes,
                solutions,
                products,
                false);
    }

    private static RequirementVersionView version() {
        return new RequirementVersionView(
                9L,
                1,
                "Need secure communication",
                "content-hash",
                null,
                "architect",
                Instant.now(),
                null);
    }

    private static RequirementView requirement(
            RequirementVersionView version,
            String currentSnapshot) {
        return new RequirementView(
                REQUIREMENT_ID,
                PROJECT_ID,
                "REQ-001",
                "Secure communication",
                RequirementStatus.DRAFT,
                50,
                Criticality.HIGH,
                RequirementType.FUNCTIONAL,
                ReviewStatus.PROPOSED,
                "architect",
                version != null ? version.id() : null,
                currentSnapshot,
                Instant.now(),
                Instant.now(),
                version);
    }

    private static AnalysisJobView job(
            String jobId,
            String operationId,
            int pass,
            int totalPasses,
            Long requirementId,
            AnalysisStatus status,
            String snapshotId,
            Instant createdAt) {
        CopilotOperationKey key = new CopilotOperationKey(
                false,
                operationId,
                pass,
                totalPasses,
                AnalysisAutomationProfile.FULL,
                true,
                true);
        return jobWithKey(jobId, key.value(), status, snapshotId, createdAt, requirementId);
    }

    private static AnalysisJobView jobWithKey(
            String jobId,
            String key,
            AnalysisStatus status,
            String snapshotId,
            Instant createdAt) {
        return jobWithKey(jobId, key, status, snapshotId, createdAt, REQUIREMENT_ID);
    }

    private static AnalysisJobView jobWithKey(
            String jobId,
            String key,
            AnalysisStatus status,
            String snapshotId,
            Instant createdAt,
            Long requirementId) {
        return rawJob(
                jobId,
                key,
                status,
                List.of(item(requirementId, status, snapshotId)),
                createdAt);
    }

    private static AnalysisJobItemView item(
            Long requirementId,
            AnalysisStatus status,
            String snapshotId) {
        return new AnalysisJobItemView(
                11L,
                requirementId,
                requirementId != null ? "REQ-" + requirementId : null,
                9L,
                1,
                status,
                snapshotId,
                1,
                Instant.now(),
                isTerminal(status) ? Instant.now() : null,
                null);
    }

    private static AnalysisJobView rawJob(
            String jobId,
            String key,
            AnalysisStatus status,
            List<AnalysisJobItemView> items,
            Instant createdAt) {
        return new AnalysisJobView(
                jobId,
                PROJECT_ID,
                status,
                key,
                "CUSTOM_OPENAI",
                50,
                "architect",
                "ws-architect",
                createdAt,
                status == AnalysisStatus.PENDING ? null : createdAt,
                isTerminal(status) ? createdAt : null,
                items != null ? items.size() : 0,
                status == AnalysisStatus.SUCCESS ? 1 : 0,
                status == AnalysisStatus.PARTIAL ? 1 : 0,
                status == AnalysisStatus.FAILED ? 1 : 0,
                null,
                items);
    }

    private static boolean isTerminal(AnalysisStatus status) {
        return status == AnalysisStatus.SUCCESS
                || status == AnalysisStatus.PARTIAL
                || status == AnalysisStatus.FAILED
                || status == AnalysisStatus.CANCELLED;
    }

    private static final class Fixture {
        final ProjectRequirementAnalysisService analysisService =
                mock(ProjectRequirementAnalysisService.class);
        final ProjectPortfolioService projectService = mock(ProjectPortfolioService.class);
        final PortfolioFingerprintService fingerprintService =
                mock(PortfolioFingerprintService.class);
        final AiAutomationPolicy policy = mock(AiAutomationPolicy.class);
        final CopilotResultSelector resultSelector = mock(CopilotResultSelector.class);
        final CopilotResultPersistenceService resultPersistenceService =
                mock(CopilotResultPersistenceService.class);
        final CopilotCompletionService completionService = mock(CopilotCompletionService.class);
        final CopilotJobControlService jobControlService = mock(CopilotJobControlService.class);
        final ExecutorService coordinator = mock(ExecutorService.class);
        final AtomicReference<Runnable> scheduled = new AtomicReference<>();
        final AtomicReference<String> persistedKey = new AtomicReference<>();
        final CopilotAutomationService service = new CopilotAutomationService(
                analysisService,
                projectService,
                fingerprintService,
                policy,
                resultSelector,
                resultPersistenceService,
                completionService,
                jobControlService,
                coordinator);
    }
}
