package com.taxonomy.analysis.service;

import com.taxonomy.analysis.controller.AnalysisSseEventMapper;
import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisCommand;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase;
import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.dto.TaxonomyDiscrepancy;
import com.taxonomy.shared.service.LocalEmbeddingService;
import com.taxonomy.shared.service.PromptTemplateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression cases for the complete-response/stop boundary; no real LLM calls. */
class AnalysisReviewRegressionTest {
    private final AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new MockEnvironment());
    private final WorkspaceContext scope = new WorkspaceContext("alice", "work-a", "draft", "repo-a");

    @AfterEach void restoresThreadScope() {
        assertThat(AnalysisRunControl.active()).isFalse();
    }

    @Test void postResponseStopRetainsLiveScoresAndDiagnosticText() {
        try (var run = registry.open(null, "alice", scope, null)) {
            var detail = new LlmCallDetail();
            detail.setScores(Map.of("BP", 80));
            detail.setReasons(Map.of("BP", "completed evidence"));
            detail.setPrompt("request before cancellation");
            detail.setRawResponse("response received before stopping");
            assertThatThrownBy(() -> AnalysisRunControl.call("MOCK", "BP", () -> {
                registry.cancel(run.id(), "alice", scope);
                return detail;
            })).isInstanceOf(AnalysisStoppedException.class);
            var state = registry.snapshot(run.id(), "alice", scope);
            assertThat(state.rawScores()).containsEntry("BP", 80);
            assertThat(state.phase()).isEqualTo("STOPPING");
            assertThat(state.stopReason()).isEqualTo("CANCELLED");
            assertThat(state.calls().getFirst().status()).isEqualTo("STOPPED");
            var log = registry.callDetail(run.id(), state.calls().getFirst().id(), "alice", scope);
            assertThat(log.prompt()).isEqualTo(detail.getPrompt());
            assertThat(log.response()).isEqualTo(detail.getRawResponse());
            run.finish("PARTIAL");
            assertThat(registry.snapshot(run.id(), "alice", scope).rawScores()).containsEntry("BP", 80);
        }
    }

    @Test void fullAnalysisRetainsTheDiscrepancyFromTheLastResponse() {
        try (var run = registry.open(null, "alice", scope, null)) {
            var service = serviceThatStopsAfterResponse(run.id());
            var result = service.analyzeWithBudget("requirement");
            assertThat(result.getStatus()).isEqualTo("PARTIAL");
            assertThat(result.getReasons()).containsEntry("BP", "completed evidence");
            assertThat(result.getDiscrepancies()).extracting(TaxonomyDiscrepancy::actualChildSum)
                    .containsExactly(150);
        }
    }

    @Test void streamingStopCarriesReasonsAndDiscrepancyThroughTheActualSseMapper() {
        try (var run = registry.open(null, "alice", scope, null)) {
            var useCase = new StreamRequirementAnalysisUseCase(serviceThatStopsAfterResponse(run.id()),
                    mock(AiPromptBudgetPolicy.class));
            var mapper = new AnalysisSseEventMapper();
            var terminal = new AtomicReference<Map<?, ?>>();
            useCase.stream(new StreamRequirementAnalysisCommand("requirement", null, Locale.ENGLISH), event -> {
                if (event instanceof AnalysisStreamEvent.Error) {
                    terminal.set((Map<?, ?>) mapper.map(event).payload());
                }
            });
            assertThat(terminal.get()).isNotNull();
            assertThat(terminal.get().get("reasons")).isEqualTo(Map.of("BP", "completed evidence"));
            assertThat((List<?>) terminal.get().get("discrepancies")).hasSize(1);
            assertThat(terminal.get().get("rawScores")).isEqualTo(Map.of("BP", 100));
        }
    }

    @Test void retentionCannotReapATerminalRunBeforeItsMetadataIsPublished() throws Exception {
        try (var handle = registry.open(null, "alice", scope, null)) {
            Object run = ReflectionTestUtils.getField(handle, "run");
            var observed = new CompletableFuture<AnalysisProgressRegistry.Snapshot>();
            Thread reader = new Thread(() -> {
                try { observed.complete(registry.snapshot(handle.id(), "alice", scope)); }
                catch (Throwable error) { observed.completeExceptionally(error); }
            });
            synchronized (run) {
                // Represent the legal intermediate state of the predecessor's synchronized finish().
                ReflectionTestUtils.setField(run, "status", "COMPLETED");
                ReflectionTestUtils.setField(run, "finishedAt", 0L);
                reader.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!observed.isDone() && reader.getState() != Thread.State.BLOCKED
                        && System.nanoTime() < deadline) Thread.onSpinWait();
                try {
                    assertThat(observed.isDone()).as("retention must wait for the run monitor").isFalse();
                    assertThat(reader.getState()).isEqualTo(Thread.State.BLOCKED);
                } finally {
                    ReflectionTestUtils.setField(run, "finishedAt", System.currentTimeMillis());
                }
            }
            assertThat(observed.get(3, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            reader.join(3000);
            assertThat(reader.isAlive()).isFalse();
        }
    }

    @Test void oldTerminalRunsStillExpireWhileFreshTerminalsRemainVisible() {
        try (var old = registry.open(null, "alice", scope, null)) {
            old.finish("SUCCESS");
            ReflectionTestUtils.setField(ReflectionTestUtils.getField(old, "run"), "finishedAt", 1L);
            try (var fresh = registry.open(null, "alice", scope, null)) {
                fresh.finish("SUCCESS");
                assertThat(registry.recent("alice", scope, null, null))
                        .extracting(AnalysisProgressRegistry.Snapshot::operationId).containsExactly(fresh.id());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"prompt", "response", "error"})
    void exactTranscriptBoundaryMarksOmittedText(String field) {
        var first = new LlmCallDetail();
        var second = new LlmCallDetail();
        int framing = "error".equals(field) ? 0 : "--- call 1 ---\n".length() + 1;
        String exact = "x".repeat(32768 - framing);
        if ("prompt".equals(field)) { first.setPrompt(exact); second.setPrompt("later prompt"); }
        else if ("response".equals(field)) { first.setRawResponse(exact); second.setRawResponse("later response"); }
        else { first.setError(exact); second.setError("later error"); }
        var accumulator = new LlmDetailAccumulator();
        accumulator.add(first);
        accumulator.add(second);
        var result = accumulator.result();
        String text = "prompt".equals(field) ? result.getPrompt()
                : "response".equals(field) ? result.getRawResponse() : result.getError();
        assertThat(text).hasSize(32768).endsWith("[diagnostic transcript truncated]");
    }

    private LlmService serviceThatStopsAfterResponse(String id) {
        var config = mock(LlmProviderConfig.class);
        var gateways = mock(LlmGatewayRegistry.class);
        var catalog = mock(TaxonomyService.class);
        var prompts = mock(PromptTemplateService.class);
        var gateway = mock(LlmGateway.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.OPENAI);
        when(config.getActiveProviderName()).thenReturn("OPENAI");
        when(config.getApiKey(LlmProvider.OPENAI)).thenReturn("test-key");
        when(gateways.getGateway(LlmProvider.OPENAI)).thenReturn(gateway);
        when(prompts.renderPrompt(any(), anyString(), anyString(), anyInt(), anyString())).thenReturn("prompt");
        var root = new TaxonomyNode();
        root.setCode("BP"); root.setNameEn("Business Processes"); root.setTaxonomyRoot("BP");
        when(catalog.getRootNodes()).thenReturn(new ArrayList<>(List.of(root)));
        when(catalog.getPathToRoot(anyString())).thenReturn(List.of());
        when(gateway.sendHttpRequest("prompt", "test-key")).thenAnswer(invocation -> {
            registry.cancel(id, "alice", scope);
            return "body";
        });
        when(gateway.extractResponseText("body"))
                .thenReturn("{\"BP\":{\"score\":150,\"reason\":\"completed evidence\"}}");
        return new LlmService(config, gateways, new ObjectMapper(), catalog, prompts,
                mock(LocalEmbeddingService.class), mock(SavedAnalysisService.class));
    }
}
