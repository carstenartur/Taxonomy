package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.AnalysisRelationGenerator;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.versioning.service.HypothesisService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyzeRequirementUseCaseTest {

    @Mock
    private LlmService llmService;

    @Mock
    private RequirementArchitectureViewService architectureViewService;

    @Mock
    private AnalysisRelationGenerator analysisRelationGenerator;

    @Mock
    private HypothesisService hypothesisService;

    @Mock
    private RepositoryStateService repositoryStateService;

    @Mock
    private WorkspaceResolver workspaceResolver;

    @Mock
    private PreferencesService preferencesService;

    @InjectMocks
    private AnalyzeRequirementUseCase useCase;

    @Test
    void analyzeCoordinatesScoringPersistenceArchitectureMetadataAndViewContext() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms", true, 7, "gemini");
        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setScores(Map.of("CP", 80, "CR", 70));

        List<RelationHypothesisDto> provisionalRelations = List.of(
                new RelationHypothesisDto("CP", "Capabilities", "CR", "Communications",
                        "REALIZES", 0.56, "compatibility matrix"));
        RequirementArchitectureView architectureView = new RequirementArchitectureView();
        ViewContext viewContext = new ViewContext("abc123", "draft", Instant.now(), true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores())).thenReturn(provisionalRelations);
        when(architectureViewService.build(
                analysisResult.getScores(),
                command.businessText(),
                command.maxArchitectureNodes(),
                provisionalRelations)).thenReturn(architectureView);
        when(preferencesService.getString("diagram.policy", "defaultImpact")).thenReturn("trace");
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(repositoryStateService.getViewContext("alice", "draft")).thenReturn(viewContext);

        AnalyzeRequirementResult result = useCase.analyze(command);

        assertThat(result.analysisResult()).isSameAs(analysisResult);
        assertThat(analysisResult.getProvisionalRelations()).isEqualTo(provisionalRelations);
        assertThat(analysisResult.getArchitectureView()).isSameAs(architectureView);
        assertThat(architectureView.getViewTitle()).isEqualTo("archview.policy.title.trace");
        assertThat(architectureView.getViewDescription()).isEqualTo("archview.policy.desc.trace");
        assertThat(architectureView.isContainmentEnabled()).isFalse();
        assertThat(architectureView.getActiveRules()).isEmpty();
        assertThat(analysisResult.getViewContext()).isSameAs(viewContext);

        verify(llmService).setRequestProvider(LlmProvider.GEMINI);
        verify(hypothesisService).persistFromAnalysis(provisionalRelations, null);
        verify(llmService).clearRequestProvider();
    }

    @Test
    void analyzeSkipsPersistenceAndArchitectureViewWhenNotApplicable() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand("Need secure voice comms", false, 20, null);
        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setScores(Map.of("CP", 80));
        ViewContext viewContext = new ViewContext("abc123", "draft", Instant.now(), true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores())).thenReturn(List.of());
        when(workspaceResolver.resolveCurrentUsername()).thenReturn("alice");
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(repositoryStateService.getViewContext("alice", "draft")).thenReturn(viewContext);

        AnalyzeRequirementResult result = useCase.analyze(command);

        assertThat(result.analysisResult().getArchitectureView()).isNull();
        assertThat(result.analysisResult().getProvisionalRelations()).isEmpty();
        verify(hypothesisService, never()).persistFromAnalysis(any(), any());
        verifyNoInteractions(architectureViewService, preferencesService);
        verify(llmService).clearRequestProvider();
    }

    @Test
    void analyzeClearsProviderOverrideWhenProviderIsUnknown() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand("Need secure voice comms", false, 20, "unknown");

        assertThatThrownBy(() -> useCase.analyze(command))
                .isInstanceOf(UnknownAnalysisProviderException.class)
                .hasMessage("Unknown provider: unknown");

        verify(llmService).clearRequestProvider();
        verifyNoInteractions(analysisRelationGenerator, architectureViewService, hypothesisService,
                repositoryStateService, workspaceResolver, preferencesService);
    }
}
