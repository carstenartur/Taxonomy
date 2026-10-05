package com.taxonomy.analysis.usecase;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.*;
import com.taxonomy.architecture.service.*;
import com.taxonomy.dto.*;
import com.taxonomy.relations.service.HypothesisService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterAnalysisUseCaseTest {
    private final LlmService llm = mock(LlmService.class);
    private final AiPromptBudgetPolicy budget = mock(AiPromptBudgetPolicy.class);
    private final ClusterAnalysisExecution execution = mock(ClusterAnalysisExecution.class);
    private final WorkspaceViewContextReadPort views = mock(WorkspaceViewContextReadPort.class);
    private final AnalyzeRequirementCommand command = new AnalyzeRequirementCommand("requirement", true, 8, "MOCK", "alice",
            new WorkspaceContext("alice", "workspace", "draft", "repo"), null,
            new AnalysisScope(Set.of("CP"), AnalysisMode.FULL));
    private final ViewContext view = new ViewContext("commit", "draft", null, false, false, false);

    @Test
    void analyzeUsesExactDurableResultWithoutLocalScoringRelationsOrSecondEnrichment() {
        var architecture = mock(RequirementArchitectureViewService.class);
        var generator = mock(AnalysisRelationGenerator.class);
        var hypotheses = mock(HypothesisService.class);
        var metadata = mock(ArchitectureReportMetadataPort.class);
        var registry = mock(AnalysisProgressRegistry.class);
        var usecase = new AnalyzeRequirementUseCase(llm, budget, architecture, generator, hypotheses, views, metadata);
        ReflectionTestUtils.setField(usecase, "clusterExecution", execution);
        ReflectionTestUtils.setField(usecase, "analysisProgressRegistry", registry);
        when(views.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(views.getViewContext("alice", "draft", command.workspaceContext())).thenReturn(view);
        var result = new AnalysisResult(Map.of("CP", 66), List.of());
        result.setArchitectureView(new RequirementArchitectureView()); result.setViewContext(view);
        when(execution.execute(any(), eq(command), same(view), any())).thenAnswer(invocation -> {
            AnalysisOperationContext context = invocation.getArgument(0);
            assertThat(context.authority()).isEqualTo(new AnalysisSourceAuthority("repo", "workspace", "draft", "commit"));
            assertThat(context.requirement().matches(command.businessText())).isTrue();
            return result;
        });
        assertThat(usecase.analyze(command).analysisResult()).isSameAs(result);
        verify(budget).requireWithinBudget("requirement", "MOCK");
        verifyNoInteractions(llm, architecture, generator, hypotheses, metadata, registry);
    }

    @Test
    void activeQuestionContinuationKeepsItsExistingCheckpointExecution() {
        var usecase = new AnalyzeRequirementUseCase(llm, budget, null, null, null, views, null);
        ReflectionTestUtils.setField(usecase, "clusterExecution", execution);
        var local = new AnalyzeRequirementCommand(command.businessText(), false, 8, "MOCK", command.username(),
                command.workspaceContext(), null, new AnalysisScope(Set.of("CP"), AnalysisMode.TAXONOMIES_ONLY));
        var result = new AnalysisResult(Map.of("CP", 66), List.of());
        when(llm.analyzeWithBudget(local.businessText(), local.analysisScope())).thenReturn(result);
        try (var checkpoint = new com.taxonomy.analysis.recovery.AnalysisCheckpointSession(
                mock(com.taxonomy.analysis.recovery.AnalysisCheckpointSession.Store.class))) {
            assertThat(usecase.analyze(local).analysisResult()).isSameAs(result);
        }
        verifyNoInteractions(execution);
        verify(llm).clearRequestProvider();
    }

    @Test
    void controllerSuppliedOperationIdentityIsPreserved() {
        var usecase = new AnalyzeRequirementUseCase(llm, budget, null, null, null, views, null);
        ReflectionTestUtils.setField(usecase, "clusterExecution", execution);
        var context = AnalysisOperationContexts.create("client-operation", command, view);
        var result = new AnalysisResult();
        when(execution.execute(eq(context), eq(command), eq(view), any())).thenReturn(result);
        assertThat(usecase.analyze(command, context, view).analysisResult()).isSameAs(result);
        verifyNoInteractions(views, llm);
    }

    @Test
    void streamForwardsPersistedSnapshotAndRestoresLocaleEvenWhenDetached() {
        var usecase = new StreamRequirementAnalysisUseCase(llm, budget);
        ReflectionTestUtils.setField(usecase, "clusterExecution", execution);
        var context = AnalysisOperationContexts.create("stream", command, view);
        var stream = new StreamRequirementAnalysisCommand(command.businessText(), "MOCK", Locale.GERMAN, command.analysisScope());
        var result = new AnalysisResult(Map.of("CP", 66), List.of()); result.setStatus("SUCCESS");
        var snapshot = new ClusterAnalysisStore.Snapshot("stream", ClusterAnalysisState.COMPLETED, 5, 1, 1, 0, 0, 1, 2, List.of(), result);
        var events = new ArrayList<AnalysisStreamEvent>();
        Locale before = LocaleContextHolder.getLocale();
        when(execution.execute(eq(context), eq(command), eq(view), any())).thenAnswer(i -> {
            assertThat(LocaleContextHolder.getLocale()).isEqualTo(Locale.GERMAN);
            i.<Consumer<ClusterAnalysisStore.Snapshot>>getArgument(3).accept(snapshot);
            return result;
        });
        usecase.stream(stream, context, command, view, events::add);
        assertThat(events).containsExactly(new AnalysisStreamEvent.DurableSnapshot(snapshot));
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(before);
        assertThatThrownBy(() -> usecase.stream(stream, context, command, view,
                event -> { throw new ClusterAnalysisObservationDetachedException(); }))
                .isInstanceOf(ClusterAnalysisObservationDetachedException.class);
        assertThat(LocaleContextHolder.getLocale()).isEqualTo(before);
        verifyNoInteractions(llm);
    }
}
