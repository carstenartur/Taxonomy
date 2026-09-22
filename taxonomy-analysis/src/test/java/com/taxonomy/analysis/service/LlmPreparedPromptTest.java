package com.taxonomy.analysis.service;

import com.taxonomy.catalog.model.TaxonomyNode;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real run control, scoped registry and final-prompt boundary; no remote calls. */
class LlmPreparedPromptTest {
    private static final String KEY = "fixture-key-not-for-diagnostics";
    private static final String PRIVATE_ERROR = "private transport address and credentials";

    @Test
    void providerFailureRetainsBoundedPromptButNotCredentialsOrPrivateExceptionMessage() {
        var failure = new IllegalStateException(PRIVATE_ERROR);
        String prompt = "Requirement:\n" + "x".repeat(20_000);
        try (var run = new Run()) {
            var gateway = rejecting(failure);
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> call(gateway, prompt)));
            var detail = run.detail(1);
            assertEquals(prompt.length(), detail.promptLength());
            assertTrue(detail.prompt().startsWith("Requirement:\n"));
            assertTrue(detail.prompt().endsWith("[truncated]"));
            assertTrue(detail.prompt().length() <= AnalysisProgressRegistry.MAX_TEXT);
            assertTrue(detail.truncated());
            assertEquals("", detail.response());
            assertEquals(0, detail.responseLength());
            assertEquals("IllegalStateException", detail.error());
            assertFalse(detail.toString().contains(PRIVATE_ERROR));
            assertFalse(detail.toString().contains(KEY));
            assertEquals("FAILED", run.registry.snapshot(run.id, "owner", Run.SCOPE).calls().getFirst().status());
            assertTrue(run.registry.snapshot(run.id, "owner", Run.SCOPE).rawScores().isEmpty());
        }
    }

    @Test
    void budgetRejectionRetainsPreparedPromptWithoutCallingTheProvider() {
        var delegate = mock(LlmGateway.class);
        when(delegate.providerName()).thenReturn("GEMINI");
        var policy = mock(AiPromptBudgetPolicy.class);
        var failure = new IllegalArgumentException("budget rejection");
        when(policy.requireWithinBudget("oversized prompt", "GEMINI")).thenThrow(failure);
        var gateway = new PromptBudgetEnforcingLlmGateway(delegate, policy);
        try (var run = new Run()) {
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> call(gateway, "oversized prompt")));
            assertEquals("oversized prompt", run.detail(1).prompt());
            assertEquals("", run.detail(1).response());
            verify(delegate, never()).sendHttpRequest(anyString(), anyString());
        }
    }

    @Test
    void aCancellationDuringProviderFailureWinsWithoutDiscardingPreparedPrompt() {
        try (var run = new Run()) {
            var delegate = mock(LlmGateway.class);
            when(delegate.providerName()).thenReturn("GEMINI");
            when(delegate.sendHttpRequest(anyString(), anyString())).thenAnswer(invocation -> {
                run.registry.cancel(run.id, "owner", Run.SCOPE);
                throw new IllegalStateException(PRIVATE_ERROR);
            });
            var gateway = new PromptBudgetEnforcingLlmGateway(delegate, mock(AiPromptBudgetPolicy.class));
            var stopped = assertThrows(AnalysisStoppedException.class,
                    () -> call(gateway, "cancelled request"));
            assertEquals(AnalysisStoppedException.Reason.CANCELLED, stopped.reason());
            run.handle.finish("ERROR");
            var snapshot = run.registry.snapshot(run.id, "owner", Run.SCOPE);
            assertEquals("CANCELLED", snapshot.status());
            assertEquals("STOPPED", snapshot.calls().getFirst().status());
            assertEquals("cancelled request", run.detail(1).prompt());
            assertEquals("", run.detail(1).error());
            assertEquals("", run.detail(1).response());
        }
    }

    @Test
    void nestedCallsRestoreTheOuterCallIdentity() {
        try (var run = new Run()) {
            var gateway = rejecting(new IllegalStateException(PRIVATE_ERROR));
            assertThrows(IllegalStateException.class, () -> AnalysisRunControl.call("GEMINI", "IP", () -> {
                assertThrows(IllegalStateException.class, () -> call(gateway, "inner prompt"));
                return transmit(gateway, "outer prompt");
            }));
            assertEquals("outer prompt", run.detail(1).prompt());
            assertEquals("inner prompt", run.detail(2).prompt());
        }
    }

    @Test
    void failuresBeforePromptConstructionCannotInheritThePreviousPrompt() {
        try (var run = new Run()) {
            assertThrows(IllegalStateException.class,
                    () -> call(rejecting(new IllegalStateException(PRIVATE_ERROR)), "first prompt"));
            assertThrows(IllegalArgumentException.class, () -> AnalysisRunControl.call("GEMINI", "IP", () -> {
                throw new IllegalArgumentException("construction failed");
            }));
            assertEquals("first prompt", run.detail(1).prompt());
            assertEquals("", run.detail(2).prompt());
            assertEquals(0, run.detail(2).promptLength());
        }
    }

    @Test
    void aGatewayCallOutsideObservationCannotOverwriteTheLastObservedCall() {
        var gateway = rejecting(new IllegalStateException(PRIVATE_ERROR));
        try (var run = new Run()) {
            assertThrows(IllegalStateException.class, () -> call(gateway, "observed prompt"));
            assertThrows(IllegalStateException.class, () -> gateway.sendHttpRequest("unobserved prompt", KEY));
            assertEquals("observed prompt", run.detail(1).prompt());
            assertEquals(1, run.registry.snapshot(run.id, "owner", Run.SCOPE).calls().size());
        }
    }

    @Test
    void preparedEvidenceIsScopedAndAbsentFromStatusSnapshots() {
        try (var run = new Run()) {
            assertThrows(IllegalStateException.class, () -> call(
                    rejecting(new IllegalStateException(PRIVATE_ERROR)), "scoped confidential requirement"));
            assertFalse(new ObjectMapper().writeValueAsString(
                    run.registry.snapshot(run.id, "owner", Run.SCOPE)).contains("scoped confidential requirement"));
            var denied = assertThrows(ResponseStatusException.class,
                    () -> run.registry.callDetail(run.id, 1, "other-owner", Run.SCOPE));
            assertEquals(404, denied.getStatusCode().value());
            var foreignWorkspace = new WorkspaceContext("owner", "other-workspace", "draft");
            assertEquals(404, assertThrows(ResponseStatusException.class,
                    () -> run.registry.callDetail(run.id, 1, "owner", foreignWorkspace)).getStatusCode().value());
            assertEquals("scoped confidential requirement", run.detail(1).prompt());
        }
    }

    @Test
    void successfulResponseStillOwnsTheCompletedEvidence() {
        var delegate = mock(LlmGateway.class);
        when(delegate.providerName()).thenReturn("GEMINI");
        when(delegate.sendHttpRequest("successful prompt", KEY)).thenReturn("provider response");
        try (var run = new Run()) {
            call(new PromptBudgetEnforcingLlmGateway(delegate, mock(AiPromptBudgetPolicy.class)), "successful prompt");
            var detail = run.detail(1);
            assertEquals("successful prompt", detail.prompt());
            assertEquals("provider response", detail.response());
            assertEquals("", detail.error());
            assertFalse(detail.truncated());
            assertEquals("COMPLETED", run.registry.snapshot(run.id, "owner", Run.SCOPE).calls().getFirst().status());
        }
    }

    @Test
    void actualServiceConstructedPromptSurvivesTheGatewayException() {
        var config = mock(LlmProviderConfig.class);
        when(config.getActiveProvider()).thenReturn(LlmProvider.GEMINI);
        when(config.getActiveProviderName()).thenReturn("GEMINI");
        when(config.getApiKey(LlmProvider.GEMINI)).thenReturn(KEY);
        var gatewayRegistry = mock(LlmGatewayRegistry.class);
        var gateway = rejecting(new IllegalStateException(PRIVATE_ERROR));
        when(gatewayRegistry.getGateway(LlmProvider.GEMINI)).thenReturn(gateway);
        var templates = new PromptTemplateService();
        templates.setTemplate("IP", "Requirement:\n{{BUSINESS_TEXT}}\nReturn {{EXPECTED_KEYS}}.\n{{NODE_LIST}}");
        var service = new LlmService(config, gatewayRegistry, new ObjectMapper(), null, templates, null, null);
        var node = new TaxonomyNode();
        node.setCode("IP");
        node.setTaxonomyRoot("IP");
        node.setNameEn("Information Products");
        try (var run = new Run()) {
            var returned = service.analyzeSingleBatchDetailed("Civilian requirement", List.of(node), 100);
            assertNotNull(returned.getError());
            var retained = run.detail(1);
            assertTrue(retained.prompt().contains("Civilian requirement"),
                    "The service's constructed prompt must survive an escaping provider failure");
            assertEquals(retained.prompt().length(), retained.promptLength());
            assertEquals("IllegalStateException", retained.error());
            assertEquals("", retained.response());
        }
    }

    private static PromptBudgetEnforcingLlmGateway rejecting(RuntimeException failure) {
        var delegate = mock(LlmGateway.class);
        when(delegate.providerName()).thenReturn("GEMINI");
        when(delegate.sendHttpRequest(anyString(), anyString())).thenThrow(failure);
        return new PromptBudgetEnforcingLlmGateway(delegate, mock(AiPromptBudgetPolicy.class));
    }

    private static LlmCallDetail call(LlmGateway gateway, String prompt) {
        return AnalysisRunControl.call("GEMINI", "IP", () -> transmit(gateway, prompt));
    }

    private static LlmCallDetail transmit(LlmGateway gateway, String prompt) {
        String response = gateway.sendHttpRequest(prompt, KEY);
        var detail = new LlmCallDetail();
        detail.setPrompt(prompt);
        detail.setRawResponse(response);
        return detail;
    }

    private static final class Run implements AutoCloseable {
        static final WorkspaceContext SCOPE = new WorkspaceContext("owner", "workspace", "draft");
        final AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
        final String id = UUID.randomUUID().toString();
        final AnalysisProgressRegistry.Handle handle = registry.open(id, "owner", SCOPE, null);
        AnalysisProgressRegistry.CallDetail detail(long callId) {
            return registry.callDetail(id, callId, "owner", SCOPE);
        }
        @Override public void close() { handle.close(); }
    }
}
