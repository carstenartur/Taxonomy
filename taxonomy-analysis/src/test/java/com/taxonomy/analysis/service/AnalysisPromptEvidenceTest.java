package com.taxonomy.analysis.service;

import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetDescriptor;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetHealth;
import com.taxonomy.analysis.dto.AiTargetDtos.AiTargetMode;
import com.taxonomy.analysis.dto.AiTargetDtos.PromptBudget;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the actual gateway budget, run control and bounded registry, without HTTP. */
class AnalysisPromptEvidenceTest {
    private static final String OWNER = "prompt-evidence-owner";
    private static final WorkspaceContext SCOPE = new WorkspaceContext(OWNER, "evidence-workspace", "draft");
    private static final String PRIVATE_ERROR = "private transport details must not enter telemetry";
    private final AnalysisProgressRegistry registry = new AnalysisProgressRegistry(new StandardEnvironment());
    private final String id = UUID.randomUUID().toString();

    @Test
    void preparedPromptIsInspectableBeforeProviderCompletion() {
        String prompt = "Information products\nCivilian requirement";
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            var gateway = gateway(100_000, sent -> {
                var retained = detail(1);
                assertEquals(prompt, retained.prompt(), "prepared prompt must be visible before the provider completes");
                assertEquals(prompt.length(), retained.promptLength());
                assertEquals("", retained.response());
                return "provider reply";
            });
            invoke(prompt, gateway);
            assertEquals("provider reply", detail(1).response());
        }
    }

    @Test
    void providerFailureRetainsPreparedPromptAndSanitizedError() {
        String prompt = "Information products\nCivilian requirement";
        RuntimeException failure = new IllegalStateException(PRIVATE_ERROR);
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> invoke(prompt, gateway(100_000, sent -> { throw failure; }))));
            var retained = detail(1);
            assertEquals(prompt, retained.prompt(), "provider failure must retain the constructed prompt");
            assertEquals(prompt.length(), retained.promptLength());
            assertEquals("", retained.response());
            assertEquals(0, retained.responseLength());
            assertEquals("IllegalStateException", retained.error());
            assertFalse(retained.error().contains(PRIVATE_ERROR));
            assertEquals("FAILED", registry.snapshot(id, OWNER, SCOPE).calls().getFirst().status());
            handle.finish("PARTIAL");
            assertEquals(prompt, detail(1).prompt(), "terminal completion must not discard prompt evidence");
            var denied = assertThrows(ResponseStatusException.class,
                    () -> registry.callDetail(id, 1, "another-owner", SCOPE));
            assertEquals(404, denied.getStatusCode().value());
        }
    }

    @Test
    void budgetRejectionRetainsPromptWithoutCallingProvider() {
        String prompt = "longer than the configured input budget";
        AtomicInteger requests = new AtomicInteger();
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            var gateway = gateway(4, sent -> { requests.incrementAndGet(); return "must not run"; });
            assertThrows(PromptBudgetExceededException.class, () -> invoke(prompt, gateway));
            assertEquals(0, requests.get());
            assertEquals(prompt, detail(1).prompt(), "budget rejection must retain the prepared prompt");
            assertEquals("PromptBudgetExceededException", detail(1).error());
            assertEquals("", detail(1).response());
        }
    }

    @Test
    void preparedPromptKeepsExistingLimitsAndOriginalLength() {
        String prompt = "x".repeat(20_000);
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            assertThrows(IllegalStateException.class,
                    () -> invoke(prompt, gateway(100_000, sent -> { throw new IllegalStateException(PRIVATE_ERROR); })));
            var retained = detail(1);
            assertEquals(prompt.length(), retained.promptLength(), "original prompt length must survive a failed request");
            assertTrue(retained.truncated());
            assertTrue(retained.prompt().length() <= AnalysisProgressRegistry.MAX_TEXT);
            assertTrue(retained.prompt().endsWith("\n[truncated]"));
            assertEquals("", retained.response());
        }
    }

    @Test
    void truncatingPromptNeverSplitsASurrogatePair() {
        String marker = "\n[truncated]";
        String prompt = "x".repeat(AnalysisProgressRegistry.MAX_TEXT - marker.length() - 1)
                + "\uD83D\uDE00" + "suffix".repeat(20);
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            // This path also guards the pre-existing completion truncation helper.
            invoke(prompt, gateway(100_000, sent -> "reply"));
            var retained = detail(1);
            assertTrue(retained.truncated());
            String prefix = retained.prompt().substring(0, retained.prompt().length() - marker.length());
            assertFalse(Character.isHighSurrogate(prefix.charAt(prefix.length() - 1)),
                    "bounded evidence must not end in half a Unicode character");
            assertEquals(prompt.length(), retained.promptLength());
        }
    }

    @Test
    void cancellationStillWinsOverAProviderFailureAndKeepsPreparedEvidence() {
        String prompt = "request already prepared";
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            var gateway = gateway(100_000, sent -> {
                registry.cancel(id, OWNER, SCOPE);
                throw new IllegalStateException(PRIVATE_ERROR);
            });
            AnalysisStoppedException stopped = assertThrows(AnalysisStoppedException.class,
                    () -> invoke(prompt, gateway));
            assertEquals(AnalysisStoppedException.Reason.CANCELLED, stopped.reason());
            assertEquals(prompt, detail(1).prompt(), "stopping must not discard an already prepared prompt");
            assertEquals("", detail(1).response());
            assertEquals("", detail(1).error(), "a cooperative stop is not a provider failure");
            assertEquals("STOPPED", registry.snapshot(id, OWNER, SCOPE).calls().getFirst().status());
        }
    }

    @Test
    void nestedCallsRestoreTheirOwnPromptOwnership() {
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            assertThrows(IllegalStateException.class, () -> AnalysisRunControl.call("GEMINI", "IP", () -> {
                assertThrows(IllegalArgumentException.class, () -> invoke("inner prompt",
                        gateway(100_000, sent -> { throw new IllegalArgumentException(PRIVATE_ERROR); })));
                return invokeUnobserved("outer prompt", gateway(100_000,
                        sent -> { throw new IllegalStateException(PRIVATE_ERROR); }));
            }));
            assertEquals("outer prompt", detail(1).prompt(), "outer prompt must not be attached to the nested call");
            assertEquals("inner prompt", detail(2).prompt());
            assertEquals("IllegalStateException", detail(1).error());
            assertEquals("IllegalArgumentException", detail(2).error());
        }
    }

    @Test
    void callsOutsideAnObservedOperationCannotOverwriteEarlierEvidence() {
        try (var handle = registry.open(id, OWNER, SCOPE, null)) {
            assertThrows(IllegalStateException.class, () -> invoke("retained prompt",
                    gateway(100_000, sent -> { throw new IllegalStateException(PRIVATE_ERROR); })));
            gateway(100_000, sent -> "unobserved reply").sendHttpRequest("unobserved prompt", "fixture-key");
            assertEquals("retained prompt", detail(1).prompt());
            assertThrows(IllegalArgumentException.class, () -> AnalysisRunControl.call("GEMINI", "IP", () -> {
                throw new IllegalArgumentException(PRIVATE_ERROR);
            }));
            assertEquals("", detail(2).prompt(), "a later pre-prompt failure must not inherit prior evidence");
            assertEquals(0, detail(2).promptLength());
        }
        assertFalse(AnalysisRunControl.active());
    }

    private AnalysisProgressRegistry.CallDetail detail(long call) {
        return registry.callDetail(id, call, OWNER, SCOPE);
    }

    private static LlmCallDetail invoke(String prompt, LlmGateway gateway) {
        return AnalysisRunControl.call("GEMINI", "IP", () -> invokeUnobserved(prompt, gateway));
    }

    private static LlmCallDetail invokeUnobserved(String prompt, LlmGateway gateway) {
        LlmCallDetail result = new LlmCallDetail();
        result.setPrompt(prompt);
        result.setRawResponse(gateway.sendHttpRequest(prompt, "fixture-key-not-a-credential"));
        result.setScores(Map.of());
        return result;
    }

    private static LlmGateway gateway(int characters, Function<String, String> reply) {
        AiTargetCatalogService catalogue = mock(AiTargetCatalogService.class);
        var target = new AiTargetDescriptor("fixture", "Fixture", "GEMINI", "fixture-model",
                AiTargetMode.REMOTE, AiTargetHealth.READY, true, false, false,
                new PromptBudget(characters, characters * 4, characters), "fixture-fingerprint", null);
        when(catalogue.describeProvider("GEMINI")).thenReturn(target);
        return new PromptBudgetEnforcingLlmGateway(new LlmGateway() {
            @Override public String providerName() { return "GEMINI"; }
            @Override public String sendHttpRequest(String prompt, String key) { return reply.apply(prompt); }
            @Override public String extractResponseText(String body) { return body; }
        }, new AiPromptBudgetPolicy(catalogue));
    }
}
