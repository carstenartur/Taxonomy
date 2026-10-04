package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.service.AiPromptBudgetPolicy;
import com.taxonomy.analysis.service.AnalysisRelationGenerator;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.LlmService;
import com.taxonomy.architecture.service.RequirementArchitectureViewService;
import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.dto.RelationHypothesisDto;
import com.taxonomy.dto.RequirementArchitectureView;
import com.taxonomy.dto.ViewContext;
import com.taxonomy.architecture.service.ArchitectureReportMetadataPort;
import com.taxonomy.export.DiagramViewMetadata;
import com.taxonomy.export.DiagramSelectionConfig;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.taxonomy.dto.AnalysisScope;
import com.taxonomy.dto.AnalysisMode;
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
    private AiPromptBudgetPolicy promptBudgetPolicy;

    @Mock
    private RequirementArchitectureViewService architectureViewService;

    @Mock
    private AnalysisRelationGenerator analysisRelationGenerator;

    @Mock
    private HypothesisService hypothesisService;

    @Mock
    private RepositoryStateService repositoryStateService;

    @Mock
    private ArchitectureReportMetadataPort preferencesService;

    @InjectMocks
    private AnalyzeRequirementUseCase useCase;

    @Mock private com.taxonomy.analysis.relations.RequirementRelationSearchService requirementRelationSearchService;

    @BeforeEach
    void injectOptionalRelationSearch() {
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "requirementRelationSearch", requirementRelationSearchService);
    }

    @Test
    void taxonomyOnlyScopeSkipsEveryRelationAndArchitecturePhase() {
        var scope = new AnalysisScope(java.util.Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY);
        var command = new AnalyzeRequirementCommand("requirement", true, 20, null, "alice",
                new WorkspaceContext("alice", "alice-ws", "draft"), null, scope);
        var evidence = new AnalysisResult(Map.of("BP", 20), List.of());
        when(llmService.analyzeWithBudget("requirement", scope)).thenReturn(evidence);
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");
        var result = useCase.analyze(command).analysisResult();
        assertThat(result.getAnalysisScope()).isEqualTo(scope);
        assertThat(result.getProvisionalRelations()).isEmpty();
        assertThat(result.getRelationSearchReport()).isNull();
        assertThat(result.getArchitectureView()).isNull();
        verifyNoInteractions(requirementRelationSearchService, analysisRelationGenerator, hypothesisService,
                architectureViewService, preferencesService);
        verify(llmService).clearRequestProvider();
    }

    @Test
    void selectedFullScopeStillSearchesRelationsFromOnlySelectedScoringEvidence() {
        var scope = new AnalysisScope(java.util.Set.of("BP"), AnalysisMode.FULL);
        var command = new AnalyzeRequirementCommand("requirement", false, 20, null, "alice",
                new WorkspaceContext("alice", "alice-ws", "draft"), null, scope);
        var evidence = new AnalysisResult(Map.of("BP", 20), List.of());
        var report = org.mockito.Mockito.mock(com.taxonomy.dto.RelationSearchReport.class);
        when(report.isSearchExhausted()).thenReturn(true);
        when(report.stopReason()).thenReturn("");
        when(llmService.analyzeWithBudget("requirement", scope)).thenReturn(evidence);
        when(requirementRelationSearchService.isEnabled()).thenReturn(true);
        when(requirementRelationSearchService.search("requirement", Map.of("BP", 20))).thenReturn(report);
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");
        var result = useCase.analyze(command).analysisResult();
        assertThat(result.getAnalysisScope()).isEqualTo(scope);
        assertThat(result.getRelationSearchReport()).isSameAs(report);
        verify(requirementRelationSearchService).search("requirement", Map.of("BP", 20));
        verifyNoInteractions(analysisRelationGenerator, hypothesisService, architectureViewService);
    }

    @Test
    void cooperativeStopInsideTheRelationTaskKeepsPartialEvidenceAndSkipsArchitecture() {
        var scope = new AnalysisScope(java.util.Set.of("BP"), AnalysisMode.FULL);
        var command = new AnalyzeRequirementCommand("requirement", true, 20, null, "alice",
                new WorkspaceContext("alice", "alice-ws", "draft"), null, scope);
        var evidence = new AnalysisResult(Map.of("BP", 20), List.of());
        evidence.setStatus("SUCCESS");
        when(llmService.analyzeWithBudget("requirement", scope)).thenReturn(evidence);
        when(requirementRelationSearchService.isEnabled()).thenReturn(true);
        when(requirementRelationSearchService.search("requirement", Map.of("BP", 20))).thenThrow(
                new com.taxonomy.analysis.service.AnalysisStoppedException(
                        com.taxonomy.analysis.service.AnalysisStoppedException.Reason.CANCELLED));
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");

        var result = useCase.analyze(command).analysisResult();

        assertThat(result.getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getErrorMessage()).startsWith("CANCELLED");
        assertThat(result.getArchitectureView()).isNull();
        verifyNoInteractions(architectureViewService, preferencesService);
        // Exact read authority is resolved once and reported on the result.
        verify(repositoryStateService, org.mockito.Mockito.times(1)).resolveWorkspaceBranch("alice");
        assertThat(com.taxonomy.analysis.dag.inprocess.InProcessAnalysisOperation.current()).isEmpty();
    }

    @Test
    void analyzeCoordinatesScoringPersistenceArchitectureMetadataAndViewContext() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms", true, 7, "gemini",
                "alice", new WorkspaceContext("alice", "alice-ws", "draft"));
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
        when(preferencesService.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(repositoryStateService.getViewContext("alice", "draft", command.workspaceContext())).thenReturn(viewContext);

        AnalyzeRequirementResult result = useCase.analyze(command);

        assertThat(result.analysisResult()).isSameAs(analysisResult);
        assertThat(analysisResult.getProvisionalRelations()).isEqualTo(provisionalRelations);
        assertThat(analysisResult.getArchitectureView()).isSameAs(architectureView);
        assertThat(architectureView.getViewTitle()).isEqualTo("archview.policy.title.trace");
        assertThat(architectureView.getViewDescription()).isEqualTo("archview.policy.desc.trace");
        assertThat(architectureView.isContainmentEnabled()).isFalse();
        assertThat(architectureView.getActiveRules()).isEmpty();
        assertThat(analysisResult.getViewContext()).isSameAs(viewContext);

        verify(promptBudgetPolicy).requireWithinBudget(
                command.businessText(), command.provider());
        verify(llmService).setRequestProvider(LlmProvider.GEMINI);
        verify(hypothesisService).persistFromAnalysis(
                provisionalRelations, null, command.workspaceContext());
        verify(llmService).clearRequestProvider();
    }

    @Test
    void projectAnalysisKeepsRelationsButDefersEveryHypothesisSideEffect() {
        WorkspaceContext workspace = new WorkspaceContext("alice", "alice-ws", "draft");
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms",
                false,
                20,
                null,
                "alice",
                workspace,
                new AnalysisProvenance(
                        41L,
                        7L,
                        "snapshot-1",
                        "portfolio:snapshot-1"));
        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setScores(Map.of("CP", 80, "CR", 70));
        List<RelationHypothesisDto> provisionalRelations = List.of(
                new RelationHypothesisDto("CP", "Capabilities", "CR", "Communications",
                        "REALIZES", 0.56, "compatibility matrix"));
        ViewContext viewContext = new ViewContext(
                "abc123", "draft", Instant.now(), true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores()))
                .thenReturn(provisionalRelations);
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(repositoryStateService.getViewContext("alice", "draft", workspace))
                .thenReturn(viewContext);

        AnalyzeRequirementResult result = useCase.analyze(command);

        assertThat(result.analysisResult().getProvisionalRelations())
                .isEqualTo(provisionalRelations);
        assertThat(result.analysisResult().getViewContext()).isSameAs(viewContext);
        verify(promptBudgetPolicy).requireWithinBudget(
                command.businessText(), command.provider());
        verify(hypothesisService, never()).persistFromAnalysis(
                any(), any(), any(WorkspaceContext.class));
        verifyNoInteractions(architectureViewService, preferencesService);
        verify(llmService).clearRequestProvider();
    }

    @Test
    void analyzeSkipsPersistenceAndArchitectureViewWhenNotApplicable() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms", false, 20, null,
                "alice", new WorkspaceContext("alice", "alice-ws", "draft"));
        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setScores(Map.of("CP", 80));
        ViewContext viewContext = new ViewContext("abc123", "draft", Instant.now(), true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores())).thenReturn(List.of());
        when(repositoryStateService.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(repositoryStateService.getViewContext("alice", "draft", command.workspaceContext())).thenReturn(viewContext);

        AnalyzeRequirementResult result = useCase.analyze(command);

        assertThat(result.analysisResult().getArchitectureView()).isNull();
        assertThat(result.analysisResult().getProvisionalRelations()).isEmpty();
        verify(promptBudgetPolicy).requireWithinBudget(
                command.businessText(), command.provider());
        verify(hypothesisService, never()).persistFromAnalysis(
                any(), any(), any(WorkspaceContext.class));
        verifyNoInteractions(architectureViewService, preferencesService);
        verify(llmService).clearRequestProvider();
    }

    @Test
    void analyzeClearsProviderOverrideWhenProviderIsUnknown() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms", false, 20, "unknown",
                "alice", WorkspaceContext.SHARED);

        assertThatThrownBy(() -> useCase.analyze(command))
                .isInstanceOf(UnknownAnalysisProviderException.class)
                .hasMessage("Unknown provider: unknown");

        verify(llmService).clearRequestProvider();
        verifyNoInteractions(promptBudgetPolicy, analysisRelationGenerator,
                architectureViewService, hypothesisService,
                repositoryStateService, preferencesService);
    }

    @Test
    void analyzeUsesWorkspaceContextUsernameForBranchResolutionWhenSharedFallback() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms", false, 20, null,
                "alice", WorkspaceContext.SHARED);
        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setScores(Map.of("CP", 80));
        ViewContext viewContext = new ViewContext(null, "draft", null, true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores())).thenReturn(List.of());
        when(repositoryStateService.resolveWorkspaceBranch("system")).thenReturn("draft");
        when(repositoryStateService.getViewContext("system", "draft", WorkspaceContext.SHARED)).thenReturn(viewContext);

        AnalyzeRequirementResult result = useCase.analyze(command);

        assertThat(result.analysisResult().getViewContext()).isSameAs(viewContext);
        verify(promptBudgetPolicy).requireWithinBudget(
                command.businessText(), command.provider());
        verify(repositoryStateService).resolveWorkspaceBranch("system");
        verify(repositoryStateService).getViewContext("system", "draft", WorkspaceContext.SHARED);
        verify(llmService).clearRequestProvider();
    }
}
