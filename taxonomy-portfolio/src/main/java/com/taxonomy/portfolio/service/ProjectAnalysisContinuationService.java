package com.taxonomy.portfolio.service;

import com.taxonomy.analysis.recovery.AnalysisContinuationService;
import com.taxonomy.dto.AnalysisRecoveryView;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationSearchReport;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisContinuationView;
import com.taxonomy.portfolio.dto.PortfolioDtos.AnalysisJobView;
import com.taxonomy.portfolio.dto.PortfolioDtos.ContinueAnalysisRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.AnalysisStatus;
import com.taxonomy.portfolio.model.RequirementAnalysisJobItem;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.repository.AnalysisContinuationReadRow;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobItemRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisJobRepository;
import com.taxonomy.portfolio.repository.RequirementAnalysisSnapshotRepository;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Bridges existing project claims to the durable question journal. A new budget
 * requires a separate, revision-bound user action; verification jobs never share
 * a journal. Provider work is performed after these short transactions return.
 */
@Service
public class ProjectAnalysisContinuationService {
    private static final Set<String> BUDGETS = Set.of("CALL_BUDGET", "SOURCE_LIMIT", "WORK_LIMIT");

    private final AnalysisContinuationService continuations;
    private final WorkspaceManager workspaces;
    private final ProjectPortfolioService projects;
    private final PortfolioAnalysisPersistenceService persistence;
    private final RequirementAnalysisJobRepository jobs;
    private final RequirementAnalysisJobItemRepository items;
    private final ProjectRequirementRepository requirements;
    private final RequirementAnalysisSnapshotRepository snapshots;
    private final PortfolioJsonCodec json;
    private final String transport;

    public ProjectAnalysisContinuationService(AnalysisContinuationService continuations,
            WorkspaceManager workspaces, ProjectPortfolioService projects, PortfolioAnalysisPersistenceService persistence,
            RequirementAnalysisJobRepository jobs, RequirementAnalysisJobItemRepository items,
            ProjectRequirementRepository requirements, RequirementAnalysisSnapshotRepository snapshots,
            PortfolioJsonCodec json, @Value("${taxonomy.analysis.transport.mode:local}") String transport) {
        this.continuations = continuations;
        this.workspaces = workspaces;
        this.projects = projects;
        this.persistence = persistence;
        this.jobs = jobs;
        this.items = items;
        this.requirements = requirements;
        this.snapshots = snapshots;
        this.json = json;
        this.transport = transport;
    }

    @Transactional
    public AnalysisContinuationView read(String jobId, Long projectId, Long itemId,
            String username, WorkspaceContext context) {
        projects.requireProject(projectId, username, context);
        var item = items.findContinuationReadRow(itemId, jobId, projectId, PortfolioScope.key(username, context))
                .orElseThrow(() -> PortfolioException.notFound("Analysis item not found: " + itemId));
        return view(item, username, context);
    }

    /** Locks the existing item; its prior snapshot is the durable continuation instruction. */
    @Transactional
    public void prepare(String jobId, Long projectId, Long itemId, ContinueAnalysisRequest request,
            String username, WorkspaceContext context) {
        if (request == null || request.snapshotId() == null || request.snapshotId().isBlank()
                || request.continuationVersion() == null || request.continuationVersion() < 0) {
            throw PortfolioException.validation("snapshotId and continuationVersion are required");
        }
        projects.requireProject(projectId, username, context);
        String scope = PortfolioScope.key(username, context);
        var item = requireItem(jobId, projectId, itemId, scope, true);
        // Lock the current immutable-version pointer as well as the item generation.
        requirements.findByIdAndProjectIdAndScopeKeyForUpdate(item.getRequirementId(), projectId, scope)
                .orElseThrow(() -> PortfolioException.notFound("Requirement not found"));
        var eligibility = view(item, username, context, false);
        if (!eligibility.available()) throw PortfolioException.conflict(eligibility.reason());
        if (!Objects.equals(request.snapshotId(), eligibility.snapshotId())
                || !Objects.equals(request.continuationVersion(), eligibility.continuationVersion())) {
            throw PortfolioException.conflict("A newer analysis revision exists; refresh before continuing");
        }
        requireProviderBinding(item, username, context);
        item.prepareBudgetContinuation();
        item.getJob().markPending();
    }

    /**
     * Local only: opening a local session would otherwise bypass the configured
     * cluster executor. Existing Artemis analyses remain on their original path.
     */
    @Transactional
    public AnalysisContinuationService.Execution begin(PortfolioAnalysisWorkQueue.WorkItem work,
            AnalysisJobView job, String username, WorkspaceContext context) {
        String limitation = scopeLimitation(username, context);
        if (work.continuationSnapshotId() == null && limitation != null) return null;
        if (limitation != null) throw PortfolioException.conflict(limitation);

        String scope = PortfolioScope.key(username, context);
        if (!Objects.equals(scope, work.scopeKey())) throw PortfolioException.notFound("Analysis item not found");
        var item = requireItem(work.jobId(), work.projectId(), work.itemId(), scope, false);
        if (item.getStatus() != AnalysisStatus.RUNNING || item.getJob().getStatus() == AnalysisStatus.CANCELLED
                || item.getAttempt() != work.attempt()
                || !Objects.equals(item.getRequirementVersionId(), work.requirementVersionId())) {
            throw PortfolioException.conflict("Analysis work item claim is no longer active");
        }
        AnalysisRequest request = new AnalysisRequest();
        request.setBusinessText(work.requirementText());
        request.setIncludeArchitectureView(true);
        request.setMaxArchitectureNodes(job.maxArchitectureNodes());
        request.setProvider(job.provider());
        request.setAnalysisReasonLength(job.analysisReasonLength() == null
                ? com.taxonomy.dto.AnalysisReasonLength.STANDARD : job.analysisReasonLength());
        request.setResumable(true);
        if (work.continuationSnapshotId() == null) {
            // Stable for the claimed attempt, distinct across retries and independent passes.
            String identity = String.join("\n", scope, work.jobId(), work.itemId().toString(),
                    work.requirementVersionId().toString(), Integer.toString(work.attempt()));
            request.setContinuationId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString());
            request.setContinuationAction("START");
        } else {
            var snapshot = requireSnapshot(item, work.continuationSnapshotId());
            if (!Objects.equals(item.getSnapshotId(), snapshot.getId())
                    || !Objects.equals(item.getRequirement().getCurrentVersionId(), work.requirementVersionId())) {
                throw PortfolioException.conflict("The requirement version or continuation snapshot changed");
            }
            AnalysisResult saved = json.read(snapshot.getAnalysisPayload(), AnalysisResult.class);
            String problem = budgetProblem(saved);
            if (problem == null) problem = questionProblem(saved.getRecovery(), username, context);
            if (problem != null) throw PortfolioException.conflict(problem);
            requireProviderBinding(item, username, context);
            rejectCancelledOperation(item);
            request.setContinuationId(saved.getRecovery().id());
            request.setContinuationVersion(saved.getRecovery().version());
            request.setContinuationAction("CONTINUE");
        }
        return continuations.begin(request, username, context, job.maxArchitectureNodes());
    }

    private AnalysisContinuationView view(AnalysisContinuationReadRow item, String username, WorkspaceContext context) {
        String problem = scopeLimitation(username, context);
        AnalysisRecoveryView recovery = null;
        if (item.snapshotId() == null) {
            problem = problem == null ? "This item has no saved partial snapshot" : problem;
        } else {
            AnalysisResult saved = json.read(requireAnalysisPayload(item), AnalysisResult.class);
            recovery = saved == null ? null : saved.getRecovery();
            if (problem == null) problem = budgetProblem(saved);
            if (problem == null && !Objects.equals(item.currentRequirementVersionId(), item.requirementVersionId()))
                problem = "The requirement version changed; start a new analysis for the current version";
            if (problem == null) {
                var durable = continuations.read(recovery.id(), username, context);
                if (durable.recovery().version() != recovery.version())
                    problem = "A newer continuation revision exists; this snapshot cannot authorize another cycle";
                else problem = budgetProblem(durable.result());
                if (problem == null) problem = questionProblem(recovery, username, context);
            }
        }
        if (problem == null && (item.itemStatus() != AnalysisStatus.PARTIAL
                || item.jobStatus() == AnalysisStatus.CANCELLED))
            problem = "Only a stopped PARTIAL item can be continued";
        if (problem == null && cancelledOperation(item.idempotencyKey(), item.projectId(), item.scopeKey()))
            problem = "The Copilot operation was cancelled";
        if (problem == null) {
            try {
                persistence.checkRetainedProviderBindingForRead(item.projectId(), item.jobId(), item.provider(),
                        java.util.List.of(new ProjectProviderBinding.Target(item.requirementId(), item.requirementVersionId())),
                        username, context);
            } catch (PortfolioException rejected) {
                problem = rejected.getMessage();
            }
        }
        return new AnalysisContinuationView(item.snapshotId(), item.requirementVersionId(),
                recovery == null ? null : recovery.id(), recovery == null ? null : recovery.version(),
                recovery == null ? null : recovery.state(), problem == null,
                problem == null ? "The saved analysis stopped at a budget limit. Continue one further bounded cycle; accepted open questions remain open." : problem);
    }

    private AnalysisContinuationView view(RequirementAnalysisJobItem item, String username, WorkspaceContext context,
            boolean checkProviderForRead) {
        String problem = scopeLimitation(username, context);
        AnalysisRecoveryView recovery = null;
        if (item.getSnapshotId() == null) {
            problem = problem == null ? "This item has no saved partial snapshot" : problem;
        } else {
            var snapshot = requireSnapshot(item, item.getSnapshotId());
            AnalysisResult saved = json.read(snapshot.getAnalysisPayload(), AnalysisResult.class);
            recovery = saved == null ? null : saved.getRecovery();
            if (problem == null) problem = budgetProblem(saved);
            if (problem == null && !Objects.equals(item.getRequirement().getCurrentVersionId(), item.getRequirementVersionId()))
                problem = "The requirement version changed; start a new analysis for the current version";
            if (problem == null) {
                var durable = continuations.read(recovery.id(), username, context);
                if (durable.recovery().version() != recovery.version())
                    problem = "A newer continuation revision exists; this snapshot cannot authorize another cycle";
                else problem = budgetProblem(durable.result());
                if (problem == null) problem = questionProblem(recovery, username, context);
            }
        }
        if (problem == null && (item.getStatus() != AnalysisStatus.PARTIAL
                || item.getJob().getStatus() == AnalysisStatus.CANCELLED))
            problem = "Only a stopped PARTIAL item can be continued";
        if (problem == null && cancelledOperation(item)) problem = "The Copilot operation was cancelled";
        if (problem == null && checkProviderForRead) {
            try {
                persistence.checkRetainedProviderBindingForRead(item.getProjectId(), item.getJobId(),
                        item.getJob().getProvider(), providerTargets(item), username, context);
            } catch (PortfolioException rejected) {
                problem = rejected.getMessage();
            }
        }
        return new AnalysisContinuationView(item.getSnapshotId(), item.getRequirementVersionId(),
                recovery == null ? null : recovery.id(), recovery == null ? null : recovery.version(),
                recovery == null ? null : recovery.state(), problem == null,
                problem == null ? "The saved analysis stopped at a budget limit. Continue one further bounded cycle; accepted open questions remain open." : problem);
    }

    private void requireProviderBinding(RequirementAnalysisJobItem item, String username, WorkspaceContext context) {
        persistence.requireRetainedProviderBinding(item.getProjectId(), item.getJobId(), item.getJob().getProvider(),
                providerTargets(item), username, context);
    }

    private static java.util.List<ProjectProviderBinding.Target> providerTargets(RequirementAnalysisJobItem item) {
        return java.util.List.of(new ProjectProviderBinding.Target(item.getRequirementId(), item.getRequirementVersionId()));
    }

    private String questionProblem(AnalysisRecoveryView recovery, String username, WorkspaceContext context) {
        return continuations.hasBlockingBudgetQuestions(recovery.id(), username, context)
                ? "Failed, unassessed or uncertain provider questions require their own decision" : null;
    }

    private String scopeLimitation(String username, WorkspaceContext context) {
        if (!"local".equalsIgnoreCase(transport))
            return "Budget continuation is available only for local execution; Artemis continuation is not supported on this project path";
        if (context == null || context.workspaceId() == null || !Objects.equals(username, context.username()))
            return "Budget continuation requires an owned workspace";
        var workspace = workspaces.getWorkspaceById(context.workspaceId());
        if (workspace == null || workspace.isShared() || workspace.isArchived()
                || !Objects.equals(username, workspace.getUsername()))
            return "Budget continuation requires the original owned, active workspace";
        return null;
    }

    /** Execution limits can advance while accepted clarifications remain open and unchanged. */
    static String budgetProblem(AnalysisResult analysis) {
        if (analysis == null || !"PARTIAL".equals(analysis.getStatus()) || analysis.getRecovery() == null)
            return "This snapshot has no resumable partial analysis";
        var recovery = analysis.getRecovery();
        if (!"STOPPED".equals(recovery.state()) || recovery.openQuestions().stream().anyMatch(question -> !recordedClarification(question)))
            return "Failed, unassessed or cancelled assessments require their own decision; budget continuation is unavailable";
        boolean clarifications = !recovery.openQuestions().isEmpty();
        if (analysis.getErrorMessage() != null && !analysis.getErrorMessage().isBlank()
                && !analysis.getErrorMessage().startsWith("RELATION_SEARCH_PARTIAL:"))
            return "The analysis has another partial-result cause; budget continuation cannot replace that recovery decision";
        if (analysis.getWarnings() != null && analysis.getWarnings().stream().anyMatch(warning ->
                warning.startsWith("ARCHITECTURE_VIEW_INCOMPLETE:") || warning.startsWith("RELATION_EVALUATION_FAILED:")
                        || java.util.Arrays.stream(com.taxonomy.analysis.service.AnalysisStoppedException.Reason.values())
                                .anyMatch(reason -> warning.startsWith(reason.name() + ":"))))
            return "The analysis contains an interruption or projection failure in addition to its budget stop";
        RelationSearchReport report = analysis.getRelationSearchReport();
        if (report == null || !report.stopReason().isBlank() && !budgetReason(report.stopReason())
                || !clarifications && report.sources().stream().anyMatch(source -> hasText(source.question()))
                || report.result().unfinished().stream().anyMatch(work -> !budgetReason(work.reason())
                        && !(clarifications && "UNRESOLVED".equals(work.reason()) && hasText(work.question())))
                || report.warnings().stream().anyMatch(warning -> !budgetWarning(warning)
                        && !(clarifications && sourceClarificationWarning(report, warning))))
            return "This partial result contains an interruption or unaccepted evidence that needs a separate recovery decision";
        boolean stopped = budgetReason(report.stopReason())
                || report.result().unfinished().stream().anyMatch(work -> budgetReason(work.reason()))
                || report.warnings().stream().anyMatch(ProjectAnalysisContinuationService::budgetWarning);
        return stopped ? null : "No resumable execution budget stop is recorded";
    }

    private static boolean recordedClarification(AnalysisRecoveryView.OpenQuestion question) {
        String prefix = "RELATION_UNRESOLVED:";
        return com.taxonomy.analysis.recovery.AnalysisCheckpointSession.isRelationQuestion(question.key())
                && !question.skipped() && !question.outcomeUncertain() && question.error() != null
                && question.error().startsWith(prefix) && !question.error().substring(prefix.length()).isBlank();
    }

    private static boolean sourceClarificationWarning(RelationSearchReport report, String warning) {
        return report.sources().stream().anyMatch(source -> hasText(source.question())
                && warning.equals("SOURCE_UNRESOLVED " + source.node().id() + ": " + source.question()));
    }

    private static boolean hasText(String value) { return value != null && !value.isBlank(); }

    private static boolean budgetReason(String reason) {
        return reason != null && BUDGETS.stream().anyMatch(code -> reason.equals(code) || reason.startsWith(code + ":"));
    }

    private static boolean budgetWarning(String warning) {
        if (budgetReason(warning)) return true;
        // A source batch can retain a deferred, never-sent corrective question.
        int separator = warning == null ? -1 : warning.indexOf("]: ");
        return warning != null && warning.startsWith("INVALID_SOURCE_RESPONSE [") && separator >= 0
                && budgetReason(warning.substring(separator + 3));
    }

    private RequirementAnalysisJobItem requireItem(String jobId, Long projectId, Long itemId,
            String scope, boolean lock) {
        return (lock ? items.findClaimForUpdate(itemId, jobId, projectId, scope)
                : items.findByIdAndJobIdAndProjectIdAndScopeKey(itemId, jobId, projectId, scope))
                .orElseThrow(() -> PortfolioException.notFound("Analysis item not found: " + itemId));
    }

    private RequirementAnalysisSnapshot requireSnapshot(RequirementAnalysisJobItem item, String snapshotId) {
        var snapshot = snapshots.findByIdAndProjectIdAndScopeKey(snapshotId, item.getProjectId(), item.getScopeKey())
                .orElseThrow(() -> PortfolioException.notFound("Analysis snapshot not found"));
        if (!Objects.equals(snapshot.getJobId(), item.getJobId())
                || !Objects.equals(snapshot.getRequirementId(), item.getRequirementId())
                || !Objects.equals(snapshot.getRequirementVersionId(), item.getRequirementVersionId()))
            throw PortfolioException.conflict("The snapshot belongs to another analysis item or requirement version");
        return snapshot;
    }

    private boolean cancelledOperation(RequirementAnalysisJobItem item) {
        return cancelledOperation(item.getJob().getIdempotencyKey(), item.getProjectId(), item.getScopeKey());
    }

    private boolean cancelledOperation(String idempotencyKey, Long projectId, String scopeKey) {
        var operation = CopilotOperationKey.parse(idempotencyKey);
        return operation.isPresent() && jobs.findByProjectIdAndScopeKeyOrderByCreatedAtDesc(projectId, scopeKey)
                .stream().filter(job -> job.getStatus() == AnalysisStatus.CANCELLED)
                .anyMatch(job -> CopilotOperationKey.parse(job.getIdempotencyKey())
                        .map(key -> key.operationId().equals(operation.get().operationId())).orElse(false));
    }

    private String requireAnalysisPayload(AnalysisContinuationReadRow item) {
        if (item.matchedSnapshotId() == null) throw PortfolioException.notFound("Analysis snapshot not found");
        if (!Objects.equals(item.snapshotJobId(), item.jobId())
                || !Objects.equals(item.snapshotRequirementId(), item.requirementId())
                || !Objects.equals(item.snapshotRequirementVersionId(), item.requirementVersionId()))
            throw PortfolioException.conflict("The snapshot belongs to another analysis item or requirement version");
        var payloads = snapshots.findAnalysisPayloadForContinuation(item.snapshotId(), item.jobId(),
                item.requirementId(), item.requirementVersionId(), item.projectId(), item.scopeKey());
        if (payloads.isEmpty()) throw PortfolioException.notFound("Analysis snapshot not found");
        return payloads.getFirst();
    }

    private void rejectCancelledOperation(RequirementAnalysisJobItem item) {
        if (cancelledOperation(item)) throw PortfolioException.conflict("The Copilot operation was cancelled");
    }
}
