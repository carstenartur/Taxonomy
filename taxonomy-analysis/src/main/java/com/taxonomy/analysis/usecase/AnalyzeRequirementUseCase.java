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

import java.util.Locale;

@Service
public class AnalyzeRequirementUseCase {

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
        if (analysisProgressRegistry == null || AnalysisRunControl.active()) {
            return analyze(command, command.provenance() == null);
        }
        // Portfolio/Copilot callers retain their durable job and claim boundaries.
        try (var run = analysisProgressRegistry.open(null, command.username(),
                command.workspaceContext(), command.provenance())) {
            AnalyzeRequirementResult result = analyze(command, command.provenance() == null);
            run.finish(result.analysisResult());
            return result;
        }
    }

    private AnalyzeRequirementResult analyze(AnalyzeRequirementCommand command,
                                             boolean persistHypotheses) {
        long startedNanos = System.nanoTime();
        try {
            applyProviderOverride(command.provider());
            promptBudgetPolicy.requireWithinBudget(
                    command.businessText(), command.provider());

            AnalysisResult result = llmService.analyzeWithBudget(command.businessText());
            if (result.getErrorMessage() == null || !isCooperativeStop(result.getErrorMessage())) {
                try {
                    AnalysisRunControl.phase("RELATIONS", null);
                    enrichWithRelationHypotheses(command, result, persistHypotheses);
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
            populateViewContext(command, result);
            result.setAnalysisDurationMillis(Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L));
            return new AnalyzeRequirementResult(result);
        } finally {
            llmService.clearRequestProvider();
        }
    }

    private static boolean isCooperativeStop(String message) {
        return message.startsWith("MEMORY_PRESSURE:") || message.startsWith("CANCELLED:")
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
                ? architectureViewService.buildFromEvidence(result.getScores(), command.maxArchitectureNodes(),
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

    private void populateViewContext(AnalyzeRequirementCommand command, AnalysisResult result) {
        String effectiveUsername = command.workspaceContext().username();
        String branch = repositoryStateService.resolveWorkspaceBranch(effectiveUsername);
        result.setViewContext(repositoryStateService.getViewContext(
                effectiveUsername,
                branch,
                command.workspaceContext()));
    }
}
