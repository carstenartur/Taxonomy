package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.cluster.*;
import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.*;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClusterAnalysisApiControllerTest {
    private final TaxonomyService taxonomy = mock(TaxonomyService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final AnalyzeRequirementUseCase analyze = mock(AnalyzeRequirementUseCase.class);
    private final StreamRequirementAnalysisUseCase stream = mock(StreamRequirementAnalysisUseCase.class);
    private final AnalysisSseEventMapper localMapper = mock(AnalysisSseEventMapper.class);
    private final RepositoryStateService state = mock(RepositoryStateService.class);
    private final WorkspaceResolver workspace = mock(WorkspaceResolver.class);
    private final AnalysisProgressRegistry localRegistry = mock(AnalysisProgressRegistry.class);
    private final WorkspaceContext source = new WorkspaceContext("alice", "workspace", "draft", "repository");
    private final ViewContext view = new ViewContext("commit", "draft", null, false, false, false);
    private MockMvc mvc;
    private AnalysisApiController controller;

    @BeforeEach void setup() {
        controller = new AnalysisApiController(taxonomy, executor, new ObjectMapper(), analyze, stream,
                mock(AnalyzeNodeChildrenUseCase.class), mock(JustifyLeafUseCase.class), localMapper,
                state, workspace, mock(MessageSource.class));
        ReflectionTestUtils.setField(controller, "clusterExecution", mock(ClusterAnalysisExecution.class));
        ReflectionTestUtils.setField(controller, "analysisProgressRegistry", localRegistry);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(taxonomy.isInitialized()).thenReturn(true);
        when(workspace.resolveCurrentUsername()).thenReturn("alice");
        when(workspace.resolveCurrentContext()).thenReturn(source);
        when(state.resolveWorkspaceBranch("alice")).thenReturn("draft");
        when(state.getViewContext("alice", "draft", source)).thenReturn(view);
        doAnswer(i -> { i.<Runnable>getArgument(0).run(); return null; }).when(executor).execute(any());
    }

    @Test void postKeepsClientOperationAndExactViewWithoutOpeningALocalRun() throws Exception {
        String operation = UUID.randomUUID().toString();
        var result = new AnalysisResult(Map.of("CP", 71), List.of()); result.setStatus("SUCCESS");
        when(analyze.analyze(any(), any(), eq(view))).thenAnswer(i -> {
            AnalysisOperationContext context = i.getArgument(1);
            assertThat(context.operationId()).isEqualTo(operation);
            assertThat(context.authority().sourceCommit()).isEqualTo("commit");
            assertThat(context.authority().workspaceId()).isEqualTo("workspace");
            return new AnalyzeRequirementResult(result);
        });
        mvc.perform(post("/api/analyze").header(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, operation)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"businessText\":\"requirement\",\"provider\":\"MOCK\"}"))
                .andExpect(status().isOk()).andExpect(header().string(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, operation))
                .andExpect(jsonPath("$.rawScores.CP").value(71));
        verifyNoInteractions(localRegistry);
        verify(analyze, never()).analyze(any());
    }

    @Test void resumableDecisionsKeepTheExistingContinuationProtocolInClusterMode() throws Exception {
        var continuation = mock(com.taxonomy.analysis.recovery.AnalysisContinuationService.class);
        var replay = mock(com.taxonomy.analysis.recovery.AnalysisContinuationService.Execution.class);
        var result = new AnalysisResult(Map.of("CP", 42), List.of());
        when(replay.replay()).thenReturn(result);
        when(continuation.begin(any(), eq("alice"), eq(source), anyInt())).thenReturn(replay);
        ReflectionTestUtils.setField(controller, "continuationService", continuation);
        mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"businessText\":\"requirement\",\"resumable\":true,\"continuationId\":\"same-continuation\",\"continuationAction\":\"RETRY\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rawScores.CP").value(42));
        verify(continuation).begin(argThat(request -> "same-continuation".equals(request.getContinuationId())
                && "RETRY".equals(request.getContinuationAction())), eq("alice"), eq(source), anyInt());
        verify(replay).close();
        verifyNoInteractions(analyze, localRegistry);
    }

    @Test void streamUsesDurableRevisionAndFrozenResultWithoutGlobalMapper() throws Exception {
        String operation = UUID.randomUUID().toString();
        doAnswer(i -> {
            AnalysisOperationContext context = i.getArgument(1);
            AnalyzeRequirementCommand command = i.getArgument(2);
            assertThat(context.operationId()).isEqualTo(operation);
            assertThat(command.workspaceContext()).isEqualTo(source);
            assertThat(i.<ViewContext>getArgument(3)).isEqualTo(view);
            AnalysisStreamEventHandler handler = i.getArgument(4);
            handler.handle(new AnalysisStreamEvent.DurableSnapshot(snapshot(operation, false)));
            handler.handle(new AnalysisStreamEvent.DurableSnapshot(snapshot(operation, true)));
            return null;
        }).when(stream).stream(any(), any(), any(), any(), any());
        mvc.perform(get("/api/analyze-stream").param("businessText", "requirement")
                        .header(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, operation))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"sequence\":2")))
                .andExpect(content().string(containsString("\"sequence\":5")))
                .andExpect(content().string(containsString("\"totalScores\":{\"CP\":71}")))
                .andExpect(content().string(containsString("\"architectureView\"")))
                .andExpect(content().string(containsString("Frozen title")));
        verifyNoInteractions(localMapper, localRegistry);
        verify(stream, never()).stream(any(), any());
    }

    @Test void disconnectedQueuedObserverStillAdmitsWithoutInterruptOrCancellation() throws Exception {
        var queued = new AtomicReference<Runnable>();
        doAnswer(i -> { queued.set(i.getArgument(0)); return null; }).when(executor).execute(any());
        doAnswer(i -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            AnalysisOperationContext context = i.getArgument(1);
            AnalysisStreamEventHandler handler = i.getArgument(4);
            assertThatThrownBy(() -> handler.handle(new AnalysisStreamEvent.DurableSnapshot(snapshot(context.operationId(), false))))
                    .isInstanceOf(ClusterAnalysisObservationDetachedException.class);
            throw new ClusterAnalysisObservationDetachedException();
        }).when(stream).stream(any(), any(), any(), any(), any());
        var pending = mvc.perform(get("/api/analyze-stream").param("businessText", "requirement"))
                .andExpect(request().asyncStarted()).andReturn();
        var async = (org.springframework.mock.web.MockAsyncContext) pending.getRequest().getAsyncContext();
        for (var listener : async.getListeners()) listener.onComplete(new jakarta.servlet.AsyncEvent(async));
        queued.get().run();
        verify(stream).stream(any(), any(), any(), any(), any());
        verifyNoInteractions(localRegistry);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private static ClusterAnalysisStore.Snapshot snapshot(String operation, boolean terminal) {
        var result = new AnalysisResult(Map.of("CP", 71), List.of()); result.setStatus("SUCCESS");
        var view = new RequirementArchitectureView(); view.setViewTitle("Frozen title"); result.setArchitectureView(view);
        return new ClusterAnalysisStore.Snapshot(operation, terminal ? ClusterAnalysisState.COMPLETED : ClusterAnalysisState.RUNNING,
                terminal ? 5 : 2, terminal ? 1 : 0, 1, terminal ? 0 : 1, 0, 1, 2, List.of(), terminal ? result : null);
    }
}
