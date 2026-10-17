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
import com.taxonomy.dto.TaxonomyNodeDto;
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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    void relationsExtensionUsesRetainedScoresAndNeverRunsNodeScoring() {
        var sourceNode = node("BP", null, "Business Processes", 0);
        sourceNode.setAnalysisRole("CATEGORY"); sourceNode.setClassificationConfidence(1d);
        var original = new AnalysisResult(Map.of("BP", 20d), List.of(sourceNode)); original.setStatus("SUCCESS");
        original.setReasons(Map.of("BP", "Original explanation.\nKeep it exactly."));
        var view = new ViewContext("commit", "draft", null, false, false, false);
        original.setViewContext(view);
        var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice",
                new WorkspaceContext("alice", "alice-ws", "draft"), null,
                new AnalysisScope(java.util.Set.of("BP"), AnalysisMode.FULL)).withRetainedAnalysis(original);
        when(repositoryStateService.getViewContext("alice", "draft", command.workspaceContext())).thenReturn(view);
        var snapshots = org.mockito.Mockito.mock(com.taxonomy.catalog.snapshot.CatalogueSnapshotService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "catalogueSnapshots", snapshots);
        when(snapshots.captureRoots(any(), any())).thenAnswer(call -> {
            var n = new com.taxonomy.catalog.model.TaxonomyNode(); n.setCode("BP"); n.setTaxonomyRoot("BP"); n.setNameEn("Business Processes"); n.setLevel(0);
            var unused = new com.taxonomy.catalog.provenance.CatalogueSourceJournal.InputReference(com.taxonomy.catalog.provenance.CatalogueSourceJournal.Use.NOT_RETAINED, null, 0);
            var provenance = new com.taxonomy.catalog.provenance.CatalogueSourceJournal.Snapshot("00000000-0000-0000-0000-000000000001", java.time.Instant.EPOCH,
                    unused, new com.taxonomy.catalog.provenance.CatalogueSourceJournal.InputReference(com.taxonomy.catalog.provenance.CatalogueSourceJournal.Use.NOT_USED, null, 0), unused);
            return List.of(new com.taxonomy.catalog.snapshot.RootCatalogueSnapshot(com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.SCHEMA_VERSION, call.getArgument(0), "BP",
                    List.of(com.taxonomy.catalog.snapshot.RootCatalogueSnapshot.Node.capture(n,
                    new com.taxonomy.catalog.service.CatalogueOverlayService.NodeMetadata("CATEGORY", List.of(), 1, false, null), false)),
                    new com.taxonomy.catalog.service.CatalogueOverlayService.OverlayMetadata(false, "fixture", null, null, null, 0), provenance));
        });
        var progress = new com.taxonomy.analysis.service.AnalysisProgressRegistry(new org.springframework.core.env.StandardEnvironment());
        String operationId = java.util.UUID.randomUUID().toString();
        when(analysisRelationGenerator.generate(original.getScores())).thenAnswer(call -> {
            var snapshot = progress.snapshot(operationId, "alice", command.workspaceContext());
            assertThat(snapshot.nodeProgress()).as("Retained source coverage is visible during relation work").isNotNull();
            assertThat(snapshot.nodeProgress().assessed()).isEqualTo(1);
            assertThat(snapshot.nodeProgress().open()).isZero();
            assertThat(snapshot.calls()).as("Seeding source coverage does not fabricate provider calls").isEmpty();
            assertThat(snapshot.phase()).isEqualTo("RELATIONS");
            return List.of();
        });
        AnalysisResult extended;
        try (var run = progress.open(operationId, "alice", command.workspaceContext(), null)) {
            extended = useCase.analyze(command).analysisResult();
        }
        assertThat(extended.getRawScores()).containsExactlyEntriesOf(original.getRawScores());
        assertThat(extended.getReasons()).containsExactlyEntriesOf(original.getReasons());
        verify(llmService, never()).analyzeWithBudget(any());
        verify(llmService, never()).analyzeWithBudget(any(), any());
        verify(analysisRelationGenerator).generate(original.getScores());
    }

    @Test
    void taxonomyOnlyScopeSkipsRelationsAndProjectsRetainedArchitecture() {
        var scope = new AnalysisScope(java.util.Set.of("BP"), AnalysisMode.TAXONOMIES_ONLY);
        var command = new AnalyzeRequirementCommand("requirement", true, 20, null, "alice",
                new WorkspaceContext("alice", "alice-ws", "draft"), null, scope);
        var evidence = assessedBusinessProcess();
        var viewContext = new ViewContext("commit", "draft", null, false, false, false);
        when(llmService.analyzeWithBudget("requirement", scope)).thenReturn(evidence);
        when(repositoryStateService.getViewContext("alice", "draft", command.workspaceContext())).thenReturn(viewContext);
        when(preferencesService.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));
        var result = useCase.analyze(command).analysisResult();
        assertThat(result.getAnalysisScope()).isEqualTo(scope);
        assertThat(result.getProvisionalRelations()).isEmpty();
        assertThat(result.getRelationSearchReport()).isNull();
        // The explicitly requested view only projects retained assessments; no relation is inferred.
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getArchitectureView()).isNotNull();
        assertThat(result.getArchitectureView().getAnalysisStatus()).isEqualTo("SUCCESS");
        assertThat(result.getArchitectureView().getIncludedElements()).extracting("nodeCode")
                .containsExactly("BP-1017");
        assertThat(result.getArchitectureView().getIncludedRelationships()).isEmpty();
        assertThat(result.getRawScores()).containsExactlyEntriesOf(Map.of("BP-1017", 20d));
        assertThat(result.getViewContext()).isSameAs(viewContext);
        verifyNoInteractions(requirementRelationSearchService, analysisRelationGenerator, hypothesisService,
                architectureViewService);
        verify(llmService).requireProviderAdmissible(null, false);
        verify(llmService).analyzeWithBudget("requirement", scope);
        verify(llmService).clearRequestProvider();
    }

    @Test
    void clusterCallerCapturesTheAuthorizedBranchWithoutAnActiveWorkspace() {
        var cluster = org.mockito.Mockito.mock(com.taxonomy.analysis.cluster.ClusterAnalysisExecution.class);
        org.springframework.test.util.ReflectionTestUtils.setField(useCase, "clusterExecution", cluster);
        var selected = new WorkspaceContext("alice", "alice-ws", "main", "repository");
        var command = new AnalyzeRequirementCommand("requirement", false, 20, "MOCK", "alice",
                selected, null, new AnalysisScope(java.util.Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
        when(repositoryStateService.getViewContext(org.mockito.ArgumentMatchers.eq("alice"),
                any(), org.mockito.ArgumentMatchers.eq(selected))).thenAnswer(i -> {
            String branch = i.getArgument(1);
            return new ViewContext("main".equals(branch) ? "main-commit" : null,
                    branch, null, false, false, false);
        });
        when(cluster.execute(any(), any(), any(), any())).thenAnswer(i -> {
            com.taxonomy.analysis.dag.AnalysisOperationContext operation = i.getArgument(0);
            ViewContext captured = i.getArgument(2);
            assertThat(operation.authority().branch()).isEqualTo("main");
            assertThat(operation.authority().sourceCommit()).isEqualTo("main-commit");
            assertThat(captured.basedOnBranch()).isEqualTo("main");
            return new AnalysisResult(Map.of("CP", 71d), List.of());
        });

        assertThat(useCase.analyze(command).analysisResult().getRawScores()).containsEntry("CP", 71d);

        verify(repositoryStateService).getViewContext("alice", "main", selected);
        verify(repositoryStateService, never()).resolveWorkspaceBranch(any());
        verify(llmService).resolveAnalysisReasonLength(null);
        verify(llmService).requireProviderAdmissible(org.mockito.ArgumentMatchers.any(), anyBoolean());
        verifyNoMoreInteractions(llmService);
    }

    @Test
    void selectedFullScopeStillSearchesRelationsFromOnlySelectedScoringEvidence() {
        var scope = new AnalysisScope(java.util.Set.of("BP"), AnalysisMode.FULL);
        var command = new AnalyzeRequirementCommand("requirement", false, 20, null, "alice",
                new WorkspaceContext("alice", "alice-ws", "draft"), null, scope);
        var evidence = new AnalysisResult(Map.of("BP", 20d), List.of());
        var report = org.mockito.Mockito.mock(com.taxonomy.dto.RelationSearchReport.class);
        when(report.isSearchExhausted()).thenReturn(true);
        when(llmService.analyzeWithBudget("requirement", scope)).thenReturn(evidence);
        when(requirementRelationSearchService.isEnabled()).thenReturn(true);
        when(requirementRelationSearchService.search("requirement", Map.of("BP", 20d))).thenReturn(report);
        var result = useCase.analyze(command).analysisResult();
        assertThat(result.getAnalysisScope()).isEqualTo(scope);
        assertThat(result.getRelationSearchReport()).isSameAs(report);
        verify(requirementRelationSearchService).search("requirement", Map.of("BP", 20d));
        verifyNoInteractions(analysisRelationGenerator, hypothesisService, architectureViewService);
    }

    @Test
    void cooperativeStopInsideTheRelationTaskKeepsPartialEvidenceAndProjectsOnlyRetainedAssessments() {
        var scope = new AnalysisScope(java.util.Set.of("BP"), AnalysisMode.FULL);
        var command = new AnalyzeRequirementCommand("requirement", true, 20, null, "alice",
                new WorkspaceContext("alice", "alice-ws", "draft"), null, scope);
        var evidence = assessedBusinessProcess();
        evidence.setStatus("SUCCESS");
        var viewContext = new ViewContext("commit", "draft", null, false, false, false);
        when(llmService.analyzeWithBudget("requirement", scope)).thenReturn(evidence);
        when(repositoryStateService.getViewContext("alice", "draft", command.workspaceContext())).thenReturn(viewContext);
        when(requirementRelationSearchService.isEnabled()).thenReturn(true);
        when(requirementRelationSearchService.search("requirement", Map.of("BP-1017", 20d))).thenThrow(
                new com.taxonomy.analysis.service.AnalysisStoppedException(
                        com.taxonomy.analysis.service.AnalysisStoppedException.Reason.CANCELLED));
        when(preferencesService.resolve()).thenReturn(DiagramViewMetadata.fromConfig(DiagramSelectionConfig.trace(), "trace"));

        var result = useCase.analyze(command).analysisResult();

        assertThat(result.getStatus()).isEqualTo("PARTIAL");
        assertThat(result.getErrorMessage()).startsWith("CANCELLED");
        assertThat(result.getArchitectureView()).isNotNull();
        assertThat(result.getArchitectureView().getAnalysisStatus()).isEqualTo("PARTIAL");
        assertThat(result.getArchitectureView().getIncludedElements()).extracting("nodeCode")
                .containsExactly("BP-1017");
        assertThat(result.getArchitectureView().getIncludedRelationships()).isEmpty();
        assertThat(result.getRawScores()).containsExactlyEntriesOf(Map.of("BP-1017", 20d));
        assertThat(result.getViewContext()).isSameAs(viewContext);
        verifyNoInteractions(architectureViewService);
        // Exact read authority is resolved once and reported on the result.
        verify(repositoryStateService, org.mockito.Mockito.times(1))
                .getViewContext("alice", "draft", command.workspaceContext());
        verify(llmService).requireProviderAdmissible(null, false);
        verify(llmService).analyzeWithBudget("requirement", scope);
        verify(requirementRelationSearchService).search("requirement", Map.of("BP-1017", 20d));
        assertThat(com.taxonomy.analysis.dag.inprocess.InProcessAnalysisOperation.current()).isEmpty();
    }

    private static AnalysisResult assessedBusinessProcess() {
        var root = node("BP-1000", null, "Business Processes", 1);
        var support = node("BP-1327", "BP-1000", "Enable", 2);
        var analysis = node("BP-1060", "BP-1327", "Analytical Support", 3);
        var assessed = node("BP-1017", "BP-1060", "Acquire Data", 4);
        root.getChildren().add(support);
        support.getChildren().add(analysis);
        analysis.getChildren().add(assessed);
        var result = new AnalysisResult(Map.of("BP-1017", 20d), List.of(root));
        result.setStatus("SUCCESS");
        return result;
    }

    private static TaxonomyNodeDto node(String code, String parentCode, String name, int level) {
        var node = new TaxonomyNodeDto();
        node.setCode(code); node.setTaxonomyRoot("BP"); node.setParentCode(parentCode);
        node.setNameEn(name); node.setLevel(level);
        return node;
    }

    @Test
    void analyzeCoordinatesScoringPersistenceArchitectureMetadataAndViewContext() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms", true, 7, "gemini",
                "alice", new WorkspaceContext("alice", "alice-ws", "draft"));
        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setScores(Map.of("CP", 80d, "CR", 70d));
        analysisResult.setStatus("SUCCESS");

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
        verify(llmService).setRequestProviderId(LlmProvider.GEMINI.id());
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
        analysisResult.setScores(Map.of("CP", 80d, "CR", 70d));
        List<RelationHypothesisDto> provisionalRelations = List.of(
                new RelationHypothesisDto("CP", "Capabilities", "CR", "Communications",
                        "REALIZES", 0.56, "compatibility matrix"));
        ViewContext viewContext = new ViewContext(
                "abc123", "draft", Instant.now(), true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores()))
                .thenReturn(provisionalRelations);
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
        analysisResult.setScores(Map.of("CP", 80d));
        ViewContext viewContext = new ViewContext("abc123", "draft", Instant.now(), true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores())).thenReturn(List.of());
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
        org.mockito.Mockito.doThrow(new IllegalArgumentException("No executable LLM provider registered"))
                .when(llmService).setRequestProviderId(new com.taxonomy.extension.api.llm.ProviderId("unknown"));
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
    void analyzeUsesTheExplicitSharedContextForViewResolution() {
        AnalyzeRequirementCommand command = new AnalyzeRequirementCommand(
                "Need secure voice comms", false, 20, null,
                "alice", WorkspaceContext.SHARED);
        AnalysisResult analysisResult = new AnalysisResult();
        analysisResult.setScores(Map.of("CP", 80d));
        ViewContext viewContext = new ViewContext(null, "draft", null, true, false, false);

        when(llmService.analyzeWithBudget(command.businessText())).thenReturn(analysisResult);
        when(analysisRelationGenerator.generate(analysisResult.getScores())).thenReturn(List.of());
        when(repositoryStateService.getViewContext("system", "draft", WorkspaceContext.SHARED)).thenReturn(viewContext);

        AnalyzeRequirementResult result = useCase.analyze(command);

        assertThat(result.analysisResult().getViewContext()).isSameAs(viewContext);
        verify(promptBudgetPolicy).requireWithinBudget(
                command.businessText(), command.provider());
        verify(repositoryStateService, never()).resolveWorkspaceBranch(any());
        verify(repositoryStateService).getViewContext("system", "draft", WorkspaceContext.SHARED);
        verify(llmService).clearRequestProvider();
    }
}
