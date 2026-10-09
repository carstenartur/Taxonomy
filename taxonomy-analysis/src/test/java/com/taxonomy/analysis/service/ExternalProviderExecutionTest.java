package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import com.taxonomy.extension.api.llm.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalProviderExecutionTest {
    @Test
    void independentProviderMetadataDoesNotRequireAnEnumConstant() {
        LlmProviderExtension external = () -> new LlmProviderDescriptor(
                "EXAMPLE_CUSTOM", "Example", false, false, true, true, List.of());
        var registry = new LlmProviderExtensionRegistry(List.of(external));
        assertThat(registry.findById(" example_custom ")).contains(external);
    }

    @Test
    void anExplicitMissingProviderCannotFallBackToAnotherConfiguredProvider() {
        var config = new LlmProviderConfig(mock(LocalEmbeddingService.class));
        ReflectionTestUtils.setField(config, "llmProviderConfig", "MISSING_PLUGIN");
        ReflectionTestUtils.setField(config, "geminiApiKey", "unused-test-credential");
        assertThatThrownBy(config::getActiveProviderName)
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MISSING_PLUGIN");
    }

    @Test
    void existingFrozenBuiltinIdentityStillOverridesWorkerDefaults() {
        var config = new LlmProviderConfig(mock(LocalEmbeddingService.class));
        try (var scope = config.withRequestProvider("OPENAI")) {
            assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.OPENAI);
            assertThat(config.getActiveProviderName()).isEqualTo("OpenAI");
        }
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.GEMINI);
    }
    @Test
    void executesAnExternalProviderThroughTheExistingServiceAndFrozenScope() {
        var fixture = new Fixture(null);
        var llm = new LlmService(fixture.config, fixture.registry, new ObjectMapper(), null, null, null, null);
        try (var scope = fixture.config.withRequestProvider(" example_custom ")) {
            assertThat(llm.callLlmRaw("captured prompt")).isEqualTo("captured result");
            assertThat(fixture.config.getActiveProviderId()).isEqualTo(new ProviderId("EXAMPLE_CUSTOM"));
        }
        assertThat(fixture.attempts).hasValue(1);
        assertThat(fixture.credential).hasValue("per-call-test-key");
        verify(fixture.budget, times(1)).requireWithinBudget("captured prompt", "EXAMPLE_CUSTOM");
        assertThat(fixture.config.getActiveProvider()).isEqualTo(LlmProvider.GEMINI);
    }

    @Test
    void aMissingExecutableProviderNeverFallsBackToABuiltinGateway() {
        var fixture = new Fixture(null);
        assertThatThrownBy(() -> fixture.registry.getGatewayById(new ProviderId("ABSENT")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ABSENT");
        assertThat(fixture.attempts).hasValue(0);
    }

    @Test
    void budgetAndCancellationRejectBeforeAnyPermitOrTransportAttempt() {
        var fixture = new Fixture(null);
        AtomicInteger permits = new AtomicInteger();
        fixture.registry.configureProviderPermits((id, checkpoint) -> {
            permits.incrementAndGet(); return () -> {};
        });
        var gateway = fixture.registry.getGatewayById(new ProviderId("EXAMPLE_CUSTOM"));
        doThrow(new IllegalArgumentException("budget exceeded")).when(fixture.budget)
                .requireWithinBudget("too large", "EXAMPLE_CUSTOM");
        assertThatThrownBy(() -> gateway.sendHttpRequest("too large", "key"))
                .hasMessage("budget exceeded");
        try (var control = AnalysisRunControl.worker("fixture-operation", () -> true, mock(AnalysisMemoryGuard.class))) {
            assertThatThrownBy(() -> gateway.sendHttpRequest("cancelled", "key"))
                    .isInstanceOf(AnalysisStoppedException.class);
        }
        assertThat(permits).hasValue(0);
        assertThat(fixture.attempts).hasValue(0);
    }

    @Test
    void typedFailureReleasesCapacityAndDoesNotExposeThirdPartySecrets() {
        var fixture = new Fixture(null);
        var acquired = new AtomicInteger(); var released = new AtomicInteger();
        fixture.registry.configureProviderPermits((id, checkpoint) -> {
            assertThat(id).isEqualTo(new ProviderId("EXAMPLE_CUSTOM"));
            acquired.incrementAndGet(); return released::incrementAndGet;
        });
        fixture.failure.set(new LlmTransportException(LlmTransportException.FailureKind.UNAVAILABLE,
                "must-not-leak-credential-or-body"));
        var gateway = fixture.registry.getGatewayById(new ProviderId("EXAMPLE_CUSTOM"));
        assertThatThrownBy(() -> gateway.sendHttpRequest("first", "key"))
                .isInstanceOf(LlmProviderException.class).hasMessage("EXAMPLE_CUSTOM: UNAVAILABLE")
                .hasNoCause();
        assertThat(gateway.sendHttpRequest("second", "key")).isEqualTo("recorded envelope");
        assertThat(acquired).hasValue(2); assertThat(released).hasValue(2);
        assertThat(fixture.attempts).hasValue(2);
        verify(fixture.budget, times(1)).requireWithinBudget("first", "EXAMPLE_CUSTOM");
        verify(fixture.budget, times(1)).requireWithinBudget("second", "EXAMPLE_CUSTOM");
    }

    @Test
    void replayDoesNotAcquireCapacityOrCallTheTransport() {
        var replay = mock(LlmRecordReplayService.class);
        when(replay.isReplayMode()).thenReturn(true);
        when(replay.replay("replayed prompt")).thenReturn(java.util.Optional.of("old envelope"));
        var fixture = new Fixture(replay);
        fixture.registry.configureProviderPermits((id, checkpoint) -> { throw new AssertionError("Replay must not acquire quota"); });
        assertThat(fixture.registry.getGatewayById(new ProviderId("EXAMPLE_CUSTOM"))
                .sendHttpRequest("replayed prompt", "key")).isEqualTo("old envelope");
        assertThat(fixture.attempts).hasValue(0);
        verify(fixture.budget).requireWithinBudget("replayed prompt", "EXAMPLE_CUSTOM");
    }

    @Test
    void builtinAliasesAlsoExposeExecutableContributionsAndOnnxStaysEmbeddingsOnly() {
        var fixture = new Fixture(null);
        var metadata = new LlmProviderExtensionRegistry(List.of(new GeminiLlmProviderExtension(),
                new OpenAiLlmProviderExtension(), new DeepSeekLlmProviderExtension(), new QwenLlmProviderExtension(),
                new LlamaLlmProviderExtension(), new MistralLlmProviderExtension(),
                new CustomOpenAiLlmProviderExtension(), new LocalOnnxLlmProviderExtension(), fixture.extension));
        var contributions = fixture.registry.executableExtensions(metadata);
        assertThat(contributions).hasSize(8);
        assertThat(contributions).allSatisfy(extension -> assertThat(extension.transport().providerName())
                .isEqualTo(extension.descriptor().providerId()));
        assertThatThrownBy(() -> fixture.registry.getGateway(LlmProvider.LOCAL_ONNX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keylessExternalProviderDoesNotRequireABuiltinCredentialMarker() {
        var fixture = new Fixture(null);
        fixture.config.configureExtensions(List.of(fixture.extension), new MockEnvironment());
        try (var scope = fixture.config.withRequestProvider("EXAMPLE_CUSTOM")) {
            assertThat(fixture.config.isProviderConfigured(new ProviderId("EXAMPLE_CUSTOM"))).isTrue();
            var llm = new LlmService(fixture.config, fixture.registry, new ObjectMapper(), null, null, null, null);
            assertThat(llm.callLlmRaw("no authentication required")).isEqualTo("captured result");
        }
        assertThat(fixture.credential).hasValue("");
        assertThat(fixture.attempts).hasValue(1);
    }

    @Test
    void builtinTransportViewMapsDecoderFailuresToThePublicFailureContract() {
        var gateway = mock(LlmGateway.class);
        when(gateway.providerName()).thenReturn("GEMINI");
        when(gateway.extractResponseText("bad envelope")).thenThrow(new LlmProviderException(
                LlmProviderException.Reason.REQUEST_REJECTED, "private response body"));
        assertThatThrownBy(() -> new ProviderTransportView(gateway).extractResponseText("bad envelope"))
                .isInstanceOfSatisfying(LlmTransportException.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(LlmTransportException.FailureKind.INVALID_RESPONSE);
                    assertThat(failure.getMessage()).doesNotContain("private response body");
                    assertThat(failure).hasNoCause();
                });
    }

    private static final class Fixture {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicReference<String> credential = new AtomicReference<>();
        final AtomicReference<LlmTransportException> failure = new AtomicReference<>();
        final AiPromptBudgetPolicy budget = mock(AiPromptBudgetPolicy.class);
        final LlmProviderConfig config = new LlmProviderConfig(mock(LocalEmbeddingService.class));
        final LlmTransportExtension extension = new LlmTransportExtension() {
            @Override public LlmProviderDescriptor descriptor() {
                return new LlmProviderDescriptor("EXAMPLE_CUSTOM", "Example", false, false, true, true, List.of());
            }
            private final LlmTransport transport = new LlmTransport() {
                @Override public String providerName() { return "EXAMPLE_CUSTOM"; }
                @Override public String sendHttpRequest(String prompt, String key) {
                    attempts.incrementAndGet(); credential.set(key);
                    var once = failure.getAndSet(null); if (once != null) throw once;
                    return "recorded envelope";
                }
                @Override public String extractResponseText(String raw) { return "captured result"; }
            };
            @Override public LlmTransport transport() { return transport; }
        };
        final LlmGatewayRegistry registry;
        Fixture(LlmRecordReplayService recordings) {
            var environment = new MockEnvironment()
                    .withProperty("taxonomy.llm.providers.example_custom.api-key", "per-call-test-key");
            config.configureExtensions(List.of(extension), environment);
            registry = new LlmGatewayRegistry(config, mock(RestTemplate.class), new ObjectMapper(),
                    (key, fallback) -> key.equals("llm.retry.max") ? 0 : fallback,
                    null, recordings, budget, List.of(extension));
            registry.configureRequestAdmission(environment);
        }
    }

}
