package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.service.AnalysisProgressRegistry;
import com.taxonomy.analysis.usecase.*;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.MessageSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual HTTP and SSE controller boundaries, including the losing side of completion races. */
class AnalysisCompletionContractTest {
    private final WorkspaceContext scope = new WorkspaceContext("alice", "alice-ws", "draft");
    private final String id = UUID.randomUUID().toString();
    private final AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new MockEnvironment());
    private final TaxonomyService taxonomy = mock(TaxonomyService.class);
    private final ExecutorService executor = mock(ExecutorService.class);
    private final AnalyzeRequirementUseCase analyze = mock(AnalyzeRequirementUseCase.class);
    private final StreamRequirementAnalysisUseCase stream = mock(StreamRequirementAnalysisUseCase.class);
    private final WorkspaceResolver resolver = mock(WorkspaceResolver.class);
    private final AnalysisSseEventMapper mapper = spy(new AnalysisSseEventMapper());
    private final ObjectMapper json = spy(new ObjectMapper());
    private final AtomicReference<Runnable> queued = new AtomicReference<>();
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(taxonomy.isInitialized()).thenReturn(true);
        when(resolver.resolveCurrentUsername()).thenReturn("alice");
        when(resolver.resolveCurrentContext()).thenReturn(scope);
        doAnswer(invocation -> { queued.set(invocation.getArgument(0)); return null; })
                .when(executor).execute(any());
        var controller = new AnalysisApiController(taxonomy, executor, json, analyze, stream,
                mock(AnalyzeNodeChildrenUseCase.class), mock(JustifyLeafUseCase.class), mapper,
                mock(RepositoryStateService.class), resolver, mock(MessageSource.class));
        ReflectionTestUtils.setField(controller, "analysisProgressRegistry", registry);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private AnalysisResult result(String status) {
        var result = new AnalysisResult(Map.of("CP", 80), List.of());
        result.setReasons(Map.of("CP", "completed provider explanation"));
        result.setWarnings(List.of("existing warning"));
        result.setErrorMessage("existing diagnostic");
        result.setStatus(status);
        return result;
    }

    @ParameterizedTest @ValueSource(strings = {"SUCCESS", "PARTIAL", "ERROR"})
    void httpResultPreservesEvidenceAndReflectsCancellationAcceptedBeforeFinalization(String status) throws Exception {
        var result = result(status);
        when(analyze.analyze(any())).thenAnswer(invocation -> {
            registry.cancel(id, "alice", scope);
            return new AnalyzeRequirementResult(result);
        });
        mvc.perform(post("/api/analyze").header(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, id)
                        .contentType("application/json").content("{\"businessText\":\"resilient communications\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.rawScores.CP").value(80))
                .andExpect(jsonPath("$.reasons.CP").value("completed provider explanation"))
                .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("CANCELLED")))
                .andExpect(jsonPath("$.errorMessage", org.hamcrest.Matchers.containsString("existing diagnostic")));
        assertThat(result.getWarnings()).contains("existing warning");
        assertThat(result.getWarnings()).anyMatch(warning -> warning.contains("CANCELLED"));
        assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("CANCELLED");
    }

    @Test void completedHttpResultCannotBeRewrittenByLaterCancellation() throws Exception {
        when(analyze.analyze(any())).thenReturn(new AnalyzeRequirementResult(result("SUCCESS")));
        mvc.perform(post("/api/analyze").header(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, id)
                        .contentType("application/json").content("{\"businessText\":\"resilient communications\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(registry.cancel(id, "alice", scope).status()).isEqualTo("COMPLETED");
    }

    @ParameterizedTest @ValueSource(strings = {"complete", "error", "throw"})
    void streamTerminalReflectsTheSameCancellationDecisionAsTheRegistry(String ending) throws Exception {
        doAnswer(invocation -> {
            AnalysisStreamEventHandler emit = invocation.getArgument(1);
            registry.cancel(id, "alice", scope);
            if ("throw".equals(ending)) throw new IllegalStateException("provider failure");
            if ("complete".equals(ending)) emit.handle(new AnalysisStreamEvent.Complete(
                    "SUCCESS", Map.of("CP", 80), List.of("existing warning"), List.of(), List.of()));
            else emit.handle(new AnalysisStreamEvent.Error("ERROR", "existing diagnostic", Map.of("CP", 80),
                    List.of("existing warning"), List.of(), List.of(), Map.of("CP", "completed explanation")));
            return null;
        }).when(stream).stream(any(), any());
        String body = executeStream();
        assertThat(body).contains("\"status\":\"PARTIAL\"", "CANCELLED").doesNotContain("\"status\":\"SUCCESS\"");
        if (!"throw".equals(ending)) assertThat(body).contains("\"CP\":80", "existing warning");
        if ("error".equals(ending)) assertThat(body).contains("completed explanation", "existing diagnostic");
        assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("CANCELLED");
    }

    @Test void finalizationPrecedesTerminalBytesAndLateCancellationCannotChangeIt() throws Exception {
        doAnswer(invocation -> {
            AnalysisStreamEvent event = invocation.getArgument(0);
            if (event instanceof AnalysisStreamEvent.Complete) {
                assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("RUNNING");
            }
            return invocation.callRealMethod();
        }).when(mapper).map(any());
        doAnswer(invocation -> {
            assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("COMPLETED");
            assertThat(registry.cancel(id, "alice", scope).status()).isEqualTo("COMPLETED");
            return invocation.callRealMethod();
        }).when(json).writeValueAsString(any());
        doAnswer(invocation -> {
            AnalysisStreamEventHandler emit = invocation.getArgument(1);
            emit.handle(new AnalysisStreamEvent.Complete("SUCCESS", Map.of("CP", 80), List.of(), List.of(), List.of()));
            // A late duplicate/error cannot reopen or contradict the finalized stream.
            emit.handle(new AnalysisStreamEvent.Error("ERROR", "late duplicate", Map.of(), List.of(), List.of(), List.of()));
            return null;
        }).when(stream).stream(any(), any());
        assertThat(executeStream()).contains("\"status\":\"SUCCESS\"").doesNotContain("late duplicate");
        verify(mapper, times(1)).map(any());
    }

    @Test void cancellationAcceptedDuringTerminalPreparationWinsBeforeAnyTerminalBytes() throws Exception {
        doAnswer(invocation -> {
            registry.cancel(id, "alice", scope);
            return invocation.callRealMethod();
        }).when(mapper).map(any());
        doAnswer(invocation -> {
            AnalysisStreamEventHandler emit = invocation.getArgument(1);
            emit.handle(new AnalysisStreamEvent.Complete("SUCCESS", Map.of("CP", 80), List.of(), List.of(), List.of()));
            return null;
        }).when(stream).stream(any(), any());
        assertThat(executeStream()).contains("\"status\":\"PARTIAL\"", "CANCELLED", "\"CP\":80")
                .doesNotContain("\"status\":\"SUCCESS\"");
        assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("CANCELLED");
    }

    @Test void streamWithoutTerminalCallbackStillClosesWithAnExplicitFailure() throws Exception {
        assertThat(executeStream()).contains("\"status\":\"ERROR\"");
        assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("ERROR");
    }

    @ParameterizedTest @ValueSource(strings = {"header", "query"})
    void centralScopeCannotReserveOrScheduleFullStreamingAnalysis(String pin) throws Exception {
        when(resolver.resolveCurrentContext()).thenReturn(new WorkspaceContext("alice", null, "draft"));
        var request = get("/api/analyze-stream").param("businessText", "resilient communications")
                .header(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, id);
        if ("header".equals(pin)) request.header("X-Taxonomy-Workspace-Id", "");
        else request.param("workspaceId", "");
        mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(executor, stream);
        assertThat(registry.recent("alice", new WorkspaceContext("alice", null, "draft"), null, null)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"complete", "error", "cancelled"})
    void terminalMappingFailureStillDeliversOneClosedEvidenceBearingTerminalEvent(String ending) throws Exception {
        doThrow(new IllegalStateException("catalogue projection unavailable")).when(mapper).map(any());
        doAnswer(invocation -> {
            AnalysisStreamEventHandler emit = invocation.getArgument(1);
            if ("cancelled".equals(ending)) registry.cancel(id, "alice", scope);
            if ("complete".equals(ending)) {
                emit.handle(new AnalysisStreamEvent.Complete("SUCCESS", Map.of("CP", 80),
                        List.of("retained warning"), List.of(), List.of()));
            } else {
                emit.handle(new AnalysisStreamEvent.Error("PARTIAL", "retained diagnostic", Map.of("CP", 80),
                        List.of("retained warning"), List.of(), List.of(), Map.of("CP", "retained reason")));
            }
            return null;
        }).when(stream).stream(any(), any());
        String body = executeStream();
        assertThat(body).contains("event:error", "\"CP\":80", "retained warning", "scoreSemanticsUnavailable");
        assertThat(body.split("event:error", -1)).hasSize(2);
        assertThat(body).doesNotContain("catalogue projection unavailable", "\"status\":\"SUCCESS\"");
        if (!"complete".equals(ending)) assertThat(body).contains("retained reason", "retained diagnostic");
        assertThat(registry.snapshot(id, "alice", scope).status())
                .isEqualTo("cancelled".equals(ending) ? "CANCELLED" : "ERROR");
        assertThat(com.taxonomy.analysis.service.AnalysisRunControl.active()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"error", "completion", "timeout"})
    void disconnectRemainsCancelledEvenWhenProviderConsumesTheInterrupt(String signal) throws Exception {
        MvcResult pending = mvc.perform(get("/api/analyze-stream").param("businessText", "resilient communications")
                        .header(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, id))
                .andExpect(request().asyncStarted()).andReturn();
        doAnswer(invocation -> {
            var async = (org.springframework.mock.web.MockAsyncContext) pending.getRequest().getAsyncContext();
            var event = new jakarta.servlet.AsyncEvent(async, new java.io.IOException("connection closed"));
            for (var listener : async.getListeners()) {
                if ("error".equals(signal)) listener.onError(event);
                else if ("timeout".equals(signal)) listener.onTimeout(event);
                else listener.onComplete(event);
            }
            // Some HTTP clients consume the transient interrupt when handling failed I/O.
            Thread.interrupted();
            assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("CANCELLING");
            assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                    com.taxonomy.analysis.service.AnalysisStoppedException.class,
                    com.taxonomy.analysis.service.AnalysisRunControl::checkpoint).reason())
                    .isEqualTo(com.taxonomy.analysis.service.AnalysisStoppedException.Reason.CANCELLED);
            throw new com.taxonomy.analysis.service.AnalysisStoppedException(
                    com.taxonomy.analysis.service.AnalysisStoppedException.Reason.CANCELLED);
        }).when(stream).stream(any(), any());
        try { queued.get().run(); } finally { Thread.interrupted(); }
        assertThat(registry.snapshot(id, "alice", scope).status()).isEqualTo("CANCELLED");
        assertThat(com.taxonomy.analysis.service.AnalysisRunControl.active()).isFalse();
    }

    private String executeStream() throws Exception {
        MvcResult pending = mvc.perform(get("/api/analyze-stream").param("businessText", "resilient communications")
                        .header(AnalysisApiController.ANALYSIS_OPERATION_ID_HEADER, id))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(queued.get()).isNotNull();
        queued.get().run();
        assertThatCode(() -> pending.getAsyncResult(1000))
                .as("Every accepted worker must publish a terminal result")
                .doesNotThrowAnyException();
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
