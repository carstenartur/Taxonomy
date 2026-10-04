package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.AnalysisRelationGenerator;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.workspace.service.WorkspaceViewContextReadPort;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.service.AnalysisRunControl;
import com.taxonomy.analysis.service.AnalysisStoppedException;

import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisTaskOutcome;
import com.taxonomy.analysis.dag.RelationAnalysisTask;
import com.taxonomy.analysis.dag.TaxonomyShardRoot;
import com.taxonomy.analysis.dag.inprocess.InProcessAnalysisOperation;
import com.taxonomy.dto.ViewContext;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AnalyzeRequirementUseCase {

    @Autowired(required = false)
    private com.taxonomy.analysis.cluster.ClusterAnalysisExecution clusterExecution;

    @Autowired
    private AnalysisProgressRegistry analysisProgressRegistry;

    @Autowired
    private com.taxonomy.analysis.relations.RequirementRelationSearchService requirementRelationSearch;

    private final LlmService llmService;
    private final AiPromptBudgetPolicy promptBudgetPolicy;
    private final RequirementArchitectureViewService architectureViewService;
    private final AnalysisRelationGenerator analysisRelationGenerator;
    private final HypothesisService hypothesisService;
    private final WorkspaceViewContextReadPort repositoryStateService;
    private final ArchitectureReportMetadataPort metadataPort;

    public AnalyzeRequirementUseCase(
            LlmService llmService,
            AiPromptBudgetPolicy promptBudgetPolicy,
            RequirementArchitectureViewService architectureViewService,
            AnalysisRelationGenerator analysisRelationGenerator,
            HypothesisService hypothesisService,
            WorkspaceViewContextReadPort repositoryStateService,
            ArchitectureReportMetadataPort metadataPort) {
        this.llmService = llmService;
        this.promptBudgetPolicy = promptBudgetPolicy;
        this.architectureViewService = architectureViewService;
        this.analysisRelationGenerator = analysisRelationGenerator;
        this.hypothesisService = hypothesisService;
        this.repositoryStateService = repositoryStateService;
        this.metadataPort = metadataPort;
    }

    /**
     * Analyze a request. Ad-hoc requests persist generated hypotheses immediately.
     * Project analyses carry immutable snapshot provenance; their caller owns a
     * durable claim boundary and therefore persists hypotheses only after that
     * claim has been revalidated and locked.
     */
    public AnalyzeRequirementResult analyze(AnalyzeRequirementCommand command) {
        if (clusterExecution != null && !com.taxonomy.analysis.recovery.AnalysisCheckpointSession.active()) {
            ViewContext view = resolveViewContext(command);
            return analyze(command, operationContext(command, view), view);
        }
        if (analysisProgressRegistry == null || AnalysisRunControl.active()) {
            return analyze(command, command.provenance() == null && !com.taxonomy.analysis.recovery.AnalysisCheckpointSession.active());
        }
        // Portfolio/Copilot callers retain their durable job and claim boundaries.
        try (var run = analysisProgressRegistry.open(null, command.username(),
                command.workspaceContext(), command.provenance())) {
            AnalyzeRequirementResult result = analyze(command, command.provenance() == null && !com.taxonomy.analysis.recovery.AnalysisCheckpointSession.active());
            run.finish(result.analysisResult());
            return result;
        }
    }

    /** The HTTP adapter supplies the operation ID before durable admission. */
    public AnalyzeRequirementResult analyze(AnalyzeRequirementCommand command, AnalysisOperationContext context,
                                             ViewContext view) {
        if (clusterExecution == null) throw new IllegalStateException("Cluster execution is not configured");
        promptBudgetPolicy.requireWithinBudget(command.businessText(), command.provider());
        // Root scoring, relation work and rendering have already been persisted when this returns.
        return new AnalyzeRequirementResult(clusterExecution.execute(context, command, view, ignored -> { }));
    }

    private AnalyzeRequirementResult analyze(AnalyzeRequirementCommand command,
                                             boolean persistHypotheses) {
        long startedNanos = System.nanoTime();
        InProcessAnalysisOperation operation = null;
        try {
            applyProviderOverride(command.provider());
            promptBudgetPolicy.requireWithinBudget(
                    command.businessText(), command.provider());
            // Exact read authority is captured once before any task and reported on the result.
            ViewContext viewContext = resolveViewContext(command);
            operation = InProcessAnalysisOperation.open(operationContext(command, viewContext), null);

            AnalysisResult result = command.analysisScope().legacyFull()
                    ? llmService.analyzeWithBudget(command.businessText())
                    : llmService.analyzeWithBudget(command.businessText(), command.analysisScope());
            result.setAnalysisScope(command.analysisScope());
            if (command.analysisScope().includesRelations()
                    && (result.getErrorMessage() == null || !isCooperativeStop(result.getErrorMessage()))) {
                try {
                    AnalysisRunControl.phase("RELATIONS", null);
                    runRelationTask(operation, command, result, persistHypotheses);
                    if (result.getRelationSearchReport() == null
                            || !isCooperativeStop(result.getRelationSearchReport().stopReason())) {
                        AnalysisRunControl.phase("ARCHITECTURE", null);
                        enrichWithArchitectureView(command, result);
                    }
                } catch (AnalysisStoppedException stopped) {
                    result.setStatus("PARTIAL");
                    result.setErrorMessage(stopped.getMessage());
                    var warnings = new java.util.ArrayList<>(result.getWarnings() == null
                            ? java.util.List.<String>of() : result.getWarnings());
                    warnings.add(stopped.getMessage());
                    result.setWarnings(warnings);
                }
            }
            result.setViewContext(viewContext);
            result.setAnalysisDurationMillis(Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
            return new AnalyzeRequirementResult(result);
        } finally {
            if (operation != null) operation.close();
            llmService.clearRequestProvider();
        }
    }

    /**
     * Operation identity shared by every task: the durable run identity when a
     * progress run is active, the exact workspace authority and a requirement
     * reference instead of the requirement text.
     */
    private static AnalysisOperationContext operationContext(AnalyzeRequirementCommand command,
                                                             ViewContext viewContext) {
        String runId = AnalysisRunControl.currentOperationId();
        String operationId = runId != null ? runId : UUID.randomUUID().toString();
        return AnalysisOperationContexts.create(operationId, command, viewContext);
    }

    /** Executes relation work as the operation's {@link RelationAnalysisTask}. */
    private void runRelationTask(InProcessAnalysisOperation operation, AnalyzeRequirementCommand command,
                                 AnalysisResult result, boolean persistHypotheses) {
        List<TaxonomyShardRoot> targets = command.analysisScope().taxonomyRoots().stream()
                .map(TaxonomyShardRoot::of).toList();
        AnalysisStoppedException[] stopped = new AnalysisStoppedException[1];
        operation.runRelationTasks(targets, task -> {
            if (!task.envelope().requirement().matches(command.businessText())) {
                throw new IllegalStateException("Task requirement reference does not match the analysed requirement");
            }
            try {
                enrichWithRelationHypotheses(command, result, persistHypotheses);
            } catch (AnalysisStoppedException stop) {
                stopped[0] = stop;
                return operation.messages().completed(task, AnalysisTaskOutcome.STOPPED, 0, stop.reason().name());
            }
            var report = result.getRelationSearchReport();
            if (report != null) {
                int edges = report.result() == null ? 0 : report.result().edges().size();
                return operation.messages().completed(task, report.isSearchExhausted()
                        ? AnalysisTaskOutcome.COMPLETED : AnalysisTaskOutcome.PARTIAL, edges, null);
            }
            int relations = result.getProvisionalRelations() == null ? 0 : result.getProvisionalRelations().size();
            return operation.messages().completed(task, AnalysisTaskOutcome.COMPLETED, relations, null);
        });
        if (stopped[0] != null) throw stopped[0];
    }

    private static boolean isCooperativeStop(String message) {
        return message.startsWith("AWAITING_DECISION:") || message.startsWith("MEMORY_PRESSURE:") || message.startsWith("CANCELLED:")
                || message.startsWith("TIME_LIMIT:");
    }

    private void applyProviderOverride(String provider) {
        if (provider == null || provider.isBlank()
                || "MOCK".equalsIgnoreCase(provider)) {
            return;
        }
        try {
            llmService.setRequestProvider(LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new UnknownAnalysisProviderException(provider);
        }
    }

    private void enrichWithRelationHypotheses(AnalyzeRequirementCommand command,
                                               AnalysisResult result,
                                               boolean persistHypotheses) {
        if (result.getScores() == null) {
            return;
        }
        if (requirementRelationSearch != null && requirementRelationSearch.isEnabled()) {
            var report = requirementRelationSearch.search(command.businessText(), result.getScores());
            result.setRelationSearchReport(report);
            // Scope, conditions and choices must not leak into globally accepted catalogue edges.
            // The existing analysis snapshot persists the typed report; adoption is a separate action.
            result.setProvisionalRelations(java.util.List.of());
            if (!report.isSearchExhausted()) {
                if (!"ERROR".equals(result.getStatus())) result.setStatus("PARTIAL");
                String summary = "RELATION_SEARCH_PARTIAL: " + report.totalCalls() + "/" + report.maxCalls()
                        + " evaluation attempts; " + report.result().unfinished().size() + " unfinished search batches."
                        + (report.stopReason().isEmpty() ? "" : " " + report.stopReason());
                if (result.getErrorMessage() == null) result.setErrorMessage(summary);
                var warnings = new java.util.ArrayList<>(result.getWarnings());
                warnings.add(summary);
                report.warnings().stream().limit(8).forEach(warnings::add);
                report.result().unfinished().stream().filter(u -> !u.question().isBlank()).limit(8)
                        .map(u -> u.sourceId() + ": " + u.question()).forEach(warnings::add);
                result.setWarnings(warnings);
            }
            return;
        }
        result.setProvisionalRelations(analysisRelationGenerator.generate(result.getScores()));
        if (persistHypotheses && !result.getProvisionalRelations().isEmpty()) {
            hypothesisService.persistFromAnalysis(
                    result.getProvisionalRelations(),
                    null,
                    command.workspaceContext());
        }
    }

    private void enrichWithArchitectureView(AnalyzeRequirementCommand command, AnalysisResult result) {
        if (!command.includeArchitectureView() || result.getScores() == null) {
            return;
        }
        RequirementArchitectureView archView = result.getRelationSearchReport() != null
                ? architectureViewService.buildFromEvidence(result.getScores(), result.getScoreDetails(), command.maxArchitectureNodes(),
                        result.getRelationSearchReport())
                : architectureViewService.build(result.getScores(), command.businessText(),
                        command.maxArchitectureNodes(), result.getProvisionalRelations());
        DiagramViewMetadata meta = metadataPort.resolve();
        archView.setViewTitle(meta.viewTitle());
        archView.setViewDescription(meta.viewDescription());
        archView.setContainmentEnabled(meta.containmentEnabled());
        archView.setActiveRules(meta.activeRules());
        result.setArchitectureView(archView);
    }

    private ViewContext resolveViewContext(AnalyzeRequirementCommand command) {
        String effectiveUsername = command.workspaceContext().username();
        String branch = repositoryStateService.resolveWorkspaceBranch(effectiveUsername);
        return repositoryStateService.getViewContext(
                effectiveUsername,
                branch,
                command.workspaceContext());
    }
}
