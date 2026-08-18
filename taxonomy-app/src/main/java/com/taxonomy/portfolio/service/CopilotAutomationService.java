package com.taxonomy.portfolio.service;

import com.taxonomy.portfolio.dto.CopilotDtos.AiAutomationStatus;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotOperationView;
import com.taxonomy.portfolio.dto.CopilotDtos.CopilotRunRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobItemView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.dto.PortfolioDtos.SnapshotDetail;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * Server-side, persisted orchestration for one requirement Copilot operation.
 *
 * <p>Each verification pass is an ordinary tenant-bound analysis job. Operation
 * identity and pass metadata are encoded in that job's idempotency key, so a page
 * reload can reconstruct and resume the operation without browser-owned state.</p>
 */
@Service
public class CopilotAutomationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CopilotAutomationService.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

    private final ProjectRequirementAnalysisService analysisService;
    private final ProjectPortfolioService projectService;
    private final PortfolioFingerprintService fingerprintService;
    private final AiAutomationPolicy policy;
    private final CopilotResultSelector resultSelector;
    private final CopilotResultPersistenceService resultPersistenceService;
    private final CopilotCompletionService completionService;
    private final CopilotJobControlService jobControlService;
    private final ExecutorService coordinator;
    private final ConcurrentMap<String, CompletableFuture<Void>> running = new ConcurrentHashMap<>();

    public CopilotAutomationService(
            ProjectRequirementAnalysisService analysisService,
            ProjectPortfolioService projectService,
            PortfolioFingerprintService fingerprintService,
            AiAutomationPolicy policy,
            CopilotResultSelector resultSelector,
            CopilotResultPersistenceService resultPersistenceService,
            CopilotCompletionService completionService,
            CopilotJobControlService jobControlService,
            @Qualifier("copilotAutomationExecutor") ExecutorService coordinator) {
        this.analysisService = analysisService;
        this.projectService = projectService;
        this.fingerprintService = fingerprintService;
        this.policy = policy;
        this.resultSelector = resultSelector;
        this.resultPersistenceService = resultPersistenceService;
        this.completionService = completionService;
        this.jobControlService = jobControlService;
        this.coordinator = coordinator;
    }

    public AiAutomationStatus status() {
        return policy.status();
    }

    public CopilotOperationView enqueueManual(
            Long projectId,
            Long requirementId,
            CopilotRunRequest request,
            String username,
            WorkspaceContext context) {
        return enqueue(
                projectId,
                requirementId,
                policy.manual(request),
                username,
                context);
    }

    public Optional<CopilotOperationView> tryAutopilot(
            Long projectId,
            Long requirementId,
            String username,
            WorkspaceContext context) {
        if (!policy.autopilotReady()) return Optional.empty();
        return Optional.of(enqueue(
                projectId,
                requirementId,
                policy.autopilot(),
                username,
                context));
    }

    public Optional<CopilotOperationView> latestOperation(
            Long projectId,
            Long requirementId,
            String username,
            WorkspaceContext context) {
        return analysisService.listJobs(projectId, username, context).stream()
                .filter(job -> job.items() != null && job.items().stream()
                        .anyMatch(item -> Objects.equals(item.requirementId(), requirementId)))
                .map(job -> CopilotOperationKey.parse(job.idempotencyKey())
                        .map(key -> new JobWithKey(job, key)))
                .flatMap(Optional::stream)
                .max(Comparator.comparing(entry -> entry.job().createdAt()))
                .map(entry -> getOperation(
                        projectId,
                        entry.key().operationId(),
                        username,
                        context));
    }

    public CopilotOperationView getOperation(
            Long projectId,
            String operationId,
            String username,
            WorkspaceContext context) {
        List<JobWithKey> jobs = jobsForOperation(projectId, operationId, username, context);
        if (jobs.isEmpty()) {
            throw PortfolioException.notFound("Copilot operation not found: " + operationId);
        }
        OperationDefinition definition = definition(jobs);
        if (!operationTerminal(jobs, definition.totalPasses())) {
            schedule(definition, username, context);
        } else {
            finalizeOperation(definition, jobs, username, context);
        }
        return view(definition, jobsForOperation(
                projectId, operationId, username, context), username, context);
    }

    public CopilotOperationView cancelOperation(
            Long projectId,
            String operationId,
            String username,
            WorkspaceContext context) {
        List<JobWithKey> jobs = jobsForOperation(projectId, operationId, username, context);
        if (jobs.isEmpty()) {
            throw PortfolioException.notFound("Copilot operation not found: " + operationId);
        }
        for (JobWithKey entry : jobs) {
            if (!isTerminal(entry.job().status())) {
                jobControlService.cancel(entry.job().id(), projectId, username, context);
            }
        }
        return view(definition(jobs), jobsForOperation(
                projectId, operationId, username, context), username, context);
    }

    private CopilotOperationView enqueue(
            Long projectId,
            Long requirementId,
            AiAutomationPolicy.RunSettings settings,
            String username,
            WorkspaceContext context) {
        RequirementView requirement = projectService.getRequirement(
                projectId, requirementId, username, context);
        RequirementVersionView version = requirement.currentVersion();
        if (version == null || version.id() == null || version.contentHash() == null) {
            throw PortfolioException.conflict(
                    "Requirement has no immutable current version to analyze");
        }
        String operationId = operationId(
                projectId, requirement, settings, username, context);
        OperationDefinition definition = new OperationDefinition(
                settings.autopilot(),
                operationId,
                projectId,
                requirementId,
                settings.profile(),
                settings.provider(),
                settings.maxArchitectureNodes(),
                settings.verificationPasses(),
                settings.proposeSolutions(),
                settings.proposeProducts());

        enqueuePass(definition, 1, username, context);
        schedule(definition, username, context);
        return view(definition, jobsForOperation(
                projectId, operationId, username, context), username, context);
    }

    private void schedule(
            OperationDefinition definition,
            String username,
            WorkspaceContext context) {
        String executionKey = PortfolioScope.key(username, context)
                + "|" + definition.operationId();
        running.computeIfAbsent(executionKey, ignored -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            coordinator.execute(() -> {
                try {
                    execute(definition, username, context);
                    future.complete(null);
                } catch (Throwable failure) {
                    LOGGER.error(
                            "Copilot operation {} for project {} requirement {} stopped unexpectedly",
                            definition.operationId(),
                            definition.projectId(),
                            definition.requirementId(),
                            failure);
                    future.completeExceptionally(failure);
                } finally {
                    running.remove(executionKey, future);
                }
            });
            return future;
        });
    }

    private void execute(
            OperationDefinition definition,
            String username,
            WorkspaceContext context) {
        for (int pass = 1; pass <= definition.totalPasses(); pass++) {
            AnalysisJobView job = enqueuePass(definition, pass, username, context);
            AnalysisJobView terminal = awaitTerminal(
                    job.id(), definition.projectId(), username, context);
            if (terminal == null || terminal.status() == AnalysisStatus.CANCELLED) {
                return;
            }
        }
        List<JobWithKey> jobs = jobsForOperation(
                definition.projectId(), definition.operationId(), username, context);
        finalizeOperation(definition, jobs, username, context);
    }

    private AnalysisJobView enqueuePass(
            OperationDefinition definition,
            int pass,
            String username,
            WorkspaceContext context) {
        CopilotOperationKey key = definition.key(pass);
        return analysisService.enqueueRequirement(
                definition.projectId(),
                definition.requirementId(),
                definition.provider(),
                definition.maxArchitectureNodes(),
                key.value(),
                username,
                context);
    }

    private AnalysisJobView awaitTerminal(
            String jobId,
            Long projectId,
            String username,
            WorkspaceContext context) {
        Instant deadline = Instant.now().plus(policy.maximumRuntime());
        while (Instant.now().isBefore(deadline)) {
            AnalysisJobView job = analysisService.getJob(
                    jobId, projectId, username, context);
            if (isTerminal(job.status())) return job;
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        LOGGER.warn("Copilot coordinator stopped waiting for job {} after {} seconds; "
                        + "the persisted job remains recoverable",
                jobId, policy.maximumRuntime().toSeconds());
        return null;
    }

    private void finalizeOperation(
            OperationDefinition definition,
            List<JobWithKey> jobs,
            String username,
            WorkspaceContext context) {
        if (!operationTerminal(jobs, definition.totalPasses())) return;
        List<SnapshotDetail> snapshots = new ArrayList<>();
        for (JobWithKey entry : jobs) {
            if (entry.job().items() == null) continue;
            for (AnalysisJobItemView item : entry.job().items()) {
                if (item.snapshotId() != null) {
                    snapshots.add(analysisService.getSnapshot(
                            definition.projectId(), item.snapshotId(), username, context));
                }
            }
        }
        resultSelector.select(snapshots).ifPresent(best -> {
            resultPersistenceService.selectCurrentSnapshot(
                    definition.projectId(),
                    definition.requirementId(),
                    best.summary().id(),
                    username,
                    context);
            completionService.enrich(
                    definition.projectId(),
                    username,
                    context,
                    definition.proposeSolutions(),
                    definition.proposeProducts());
        });
    }

    private CopilotOperationView view(
            OperationDefinition definition,
            List<JobWithKey> entries,
            String username,
            WorkspaceContext context) {
        List<JobWithKey> ordered = entries.stream()
                .sorted(Comparator.comparingInt(entry -> entry.key().pass()))
                .toList();
        List<AnalysisJobView> jobs = ordered.stream().map(JobWithKey::job).toList();
        AnalysisStatus status = aggregateStatus(ordered, definition.totalPasses());
        int completed = (int) jobs.stream().filter(job -> isTerminal(job.status())).count();
        Set<String> snapshots = new LinkedHashSet<>();
        for (AnalysisJobView job : jobs) {
            if (job.items() == null) continue;
            job.items().stream().map(AnalysisJobItemView::snapshotId)
                    .filter(Objects::nonNull).forEach(snapshots::add);
        }
        String current = projectService.getRequirement(
                definition.projectId(), definition.requirementId(), username, context)
                .currentAnalysisSnapshotId();
        String selected = snapshots.contains(current) ? current : null;
        return new CopilotOperationView(
                definition.operationId(),
                definition.projectId(),
                definition.requirementId(),
                definition.profile(),
                policy.costPolicy(),
                definition.autopilot(),
                definition.provider(),
                definition.maxArchitectureNodes(),
                definition.totalPasses(),
                completed,
                status,
                definition.proposeSolutions(),
                definition.proposeProducts(),
                selected,
                message(status, completed, definition.totalPasses()),
                jobs,
                automaticSteps(definition),
                AiAutomationPolicy.HUMAN_REVIEW_REQUIRED);
    }

    private List<JobWithKey> jobsForOperation(
            Long projectId,
            String operationId,
            String username,
            WorkspaceContext context) {
        return analysisService.listJobs(projectId, username, context).stream()
                .map(job -> CopilotOperationKey.parse(job.idempotencyKey())
                        .map(key -> new JobWithKey(job, key)))
                .flatMap(Optional::stream)
                .filter(entry -> entry.key().operationId().equals(operationId))
                .sorted(Comparator.comparingInt(entry -> entry.key().pass()))
                .toList();
    }

    private static OperationDefinition definition(List<JobWithKey> jobs) {
        JobWithKey first = jobs.get(0);
        AnalysisJobItemView item = first.job().items() != null
                ? first.job().items().stream().findFirst().orElse(null) : null;
        if (item == null) {
            throw PortfolioException.conflict(
                    "Copilot analysis job contains no requirement item");
        }
        CopilotOperationKey key = first.key();
        for (JobWithKey entry : jobs) {
            if (!entry.key().operationId().equals(key.operationId())
                    || entry.key().totalPasses() != key.totalPasses()
                    || entry.key().profile() != key.profile()
                    || entry.key().autopilot() != key.autopilot()) {
                throw PortfolioException.conflict(
                        "Copilot operation metadata is inconsistent across analysis jobs");
            }
        }
        return new OperationDefinition(
                key.autopilot(),
                key.operationId(),
                first.job().projectId(),
                item.requirementId(),
                key.profile(),
                first.job().provider(),
                first.job().maxArchitectureNodes(),
                key.totalPasses(),
                key.proposeSolutions(),
                key.proposeProducts());
    }

    private String operationId(
            Long projectId,
            RequirementView requirement,
            AiAutomationPolicy.RunSettings settings,
            String username,
            WorkspaceContext context) {
        RequirementVersionView version = requirement.currentVersion();
        String material = String.join("\n",
                PortfolioScope.key(username, context),
                String.valueOf(projectId),
                String.valueOf(requirement.id()),
                String.valueOf(version.id()),
                version.contentHash(),
                fingerprintService.taxonomyFingerprint(),
                fingerprintService.promptFingerprint(),
                settings.provider(),
                String.valueOf(settings.maxArchitectureNodes()),
                settings.profile().name(),
                String.valueOf(settings.verificationPasses()),
                String.valueOf(settings.proposeSolutions()),
                String.valueOf(settings.proposeProducts()),
                settings.force() ? UUID.randomUUID().toString() : "stable");
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static AnalysisStatus aggregateStatus(
            List<JobWithKey> jobs,
            int expectedPasses) {
        if (jobs.stream().anyMatch(entry -> entry.job().status() == AnalysisStatus.RUNNING)) {
            return AnalysisStatus.RUNNING;
        }
        if (jobs.stream().anyMatch(entry -> entry.job().status() == AnalysisStatus.PENDING)) {
            return AnalysisStatus.PENDING;
        }
        if (jobs.stream().anyMatch(entry -> entry.job().status() == AnalysisStatus.CANCELLED)) {
            return AnalysisStatus.CANCELLED;
        }
        if (jobs.size() < expectedPasses) return AnalysisStatus.PENDING;
        long success = jobs.stream()
                .filter(entry -> entry.job().status() == AnalysisStatus.SUCCESS).count();
        long partial = jobs.stream()
                .filter(entry -> entry.job().status() == AnalysisStatus.PARTIAL).count();
        long failed = jobs.stream()
                .filter(entry -> entry.job().status() == AnalysisStatus.FAILED).count();
        if (success == expectedPasses) return AnalysisStatus.SUCCESS;
        if (success > 0 || partial > 0) return AnalysisStatus.PARTIAL;
        if (failed == expectedPasses) return AnalysisStatus.FAILED;
        return AnalysisStatus.PENDING;
    }

    private static boolean operationTerminal(List<JobWithKey> jobs, int expectedPasses) {
        if (jobs.stream().anyMatch(entry -> entry.job().status() == AnalysisStatus.CANCELLED)) {
            return true;
        }
        return jobs.size() == expectedPasses
                && jobs.stream().allMatch(entry -> isTerminal(entry.job().status()));
    }

    private static boolean isTerminal(AnalysisStatus status) {
        return status == AnalysisStatus.SUCCESS
                || status == AnalysisStatus.PARTIAL
                || status == AnalysisStatus.FAILED
                || status == AnalysisStatus.CANCELLED;
    }

    private static String message(AnalysisStatus status, int completed, int total) {
        return switch (status) {
            case PENDING -> "Copilot is queued (" + completed + "/" + total + " passes complete).";
            case RUNNING -> "Copilot is analyzing the requirement (" + completed + "/" + total
                    + " passes complete).";
            case SUCCESS -> "Full analysis is complete and ready for human review.";
            case PARTIAL -> "Analysis completed with partial results; review warnings and failed passes.";
            case FAILED -> "All Copilot passes failed. The persisted jobs can be retried safely.";
            case CANCELLED -> "Copilot was cancelled. Completed immutable snapshots remain available.";
        };
    }

    private static List<String> automaticSteps(OperationDefinition definition) {
        List<String> steps = new ArrayList<>(AiAutomationPolicy.AUTOMATIC_STEPS.subList(0, 6));
        if (definition.proposeSolutions()) {
            steps.add(AiAutomationPolicy.AUTOMATIC_STEPS.get(6));
        }
        if (definition.proposeProducts()) {
            steps.add(AiAutomationPolicy.AUTOMATIC_STEPS.get(7));
        }
        return List.copyOf(steps);
    }

    private record JobWithKey(AnalysisJobView job, CopilotOperationKey key) {
    }

    private record OperationDefinition(
            boolean autopilot,
            String operationId,
            Long projectId,
            Long requirementId,
            com.taxonomy.portfolio.model.AnalysisAutomationProfile profile,
            String provider,
            int maxArchitectureNodes,
            int totalPasses,
            boolean proposeSolutions,
            boolean proposeProducts) {

        CopilotOperationKey key(int pass) {
            return new CopilotOperationKey(
                    autopilot,
                    operationId,
                    pass,
                    totalPasses,
                    profile,
                    proposeSolutions,
                    proposeProducts);
        }
    }
}
