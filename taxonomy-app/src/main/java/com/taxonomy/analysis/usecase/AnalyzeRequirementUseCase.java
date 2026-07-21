package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.AnalysisRelationGenerator;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.shared.config.ExportConfig;
import com.taxonomy.versioning.service.HypothesisService;
import com.taxonomy.versioning.service.RepositoryStateService;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AnalyzeRequirementUseCase {

    private final LlmService llmService;
    private final RequirementArchitectureViewService architectureViewService;
    private final AnalysisRelationGenerator analysisRelationGenerator;
    private final HypothesisService hypothesisService;
    private final RepositoryStateService repositoryStateService;
    private final PreferencesService preferencesService;

    public AnalyzeRequirementUseCase(LlmService llmService,
                                     RequirementArchitectureViewService architectureViewService,
                                     AnalysisRelationGenerator analysisRelationGenerator,
                                     HypothesisService hypothesisService,
                                     RepositoryStateService repositoryStateService,
                                     PreferencesService preferencesService) {
        this.llmService = llmService;
        this.architectureViewService = architectureViewService;
        this.analysisRelationGenerator = analysisRelationGenerator;
        this.hypothesisService = hypothesisService;
        this.repositoryStateService = repositoryStateService;
        this.preferencesService = preferencesService;
    }

    public AnalyzeRequirementResult analyze(AnalyzeRequirementCommand command) {
        try {
            applyProviderOverride(command.provider());

            AnalysisResult result = llmService.analyzeWithBudget(command.businessText());
            enrichWithRelationHypotheses(command, result);
            enrichWithArchitectureView(command, result);
            populateViewContext(command, result);
            return new AnalyzeRequirementResult(result);
        } finally {
            llmService.clearRequestProvider();
        }
    }

    private void applyProviderOverride(String provider) {
        if (provider == null || provider.isBlank()) {
            return;
        }
        try {
            llmService.setRequestProvider(LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new UnknownAnalysisProviderException(provider);
        }
    }

    private void enrichWithRelationHypotheses(AnalyzeRequirementCommand command,
                                               AnalysisResult result) {
        if (result.getScores() == null) {
            return;
        }
        result.setProvisionalRelations(analysisRelationGenerator.generate(result.getScores()));
        if (!result.getProvisionalRelations().isEmpty()) {
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
        RequirementArchitectureView archView = architectureViewService.build(
                result.getScores(),
                command.businessText(),
                command.maxArchitectureNodes(),
                result.getProvisionalRelations());
        DiagramViewMetadata meta = ExportConfig.resolveViewMetadata(preferencesService);
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
