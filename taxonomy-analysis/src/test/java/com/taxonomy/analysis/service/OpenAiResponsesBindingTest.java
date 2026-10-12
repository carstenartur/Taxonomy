package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import com.taxonomy.extension.api.llm.*;
import com.taxonomy.extension.api.plugin.*;
import com.taxonomy.extension.runtime.PluginCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenAiResponsesBindingTest {
    private final LlmProviderConfig config = new LlmProviderConfig(mock(LocalEmbeddingService.class));
    private final AtomicReference<ProviderRateQuotas.TokenBudget> budget = new AtomicReference<>();
    OpenAiResponsesBindingTest() {
        var catalog = new PluginCatalog();
        var extension = new LlmProviderExtension() {
            public LlmProviderDescriptor descriptor() { return new LlmProviderDescriptor("OPENAI", "OpenAI", false, false, true, true, List.of()); }
        };
        catalog.publish(new PluginDescriptor(new PluginIdentity("builtin.openai", "1.0.0", "e".repeat(64)),
                ">=1.0.0", List.of(), Set.of(), PluginMode.STARTUP), List.of(extension));
        ReflectionTestUtils.setField(config, "catalog", catalog);
        config.configureProviderQuotas(new ProviderRateQuotas() {
            public TokenBudget requiredBudget(ProviderId provider) { return budget.get(); }
            public Decision tryReserve(ProviderId provider, String id, TokenBudget value, Runnable checkpoint) { throw new AssertionError("Fingerprinting must perform no quota/HTTP I/O"); }
            public void defer(ProviderId provider, long delay) { throw new AssertionError("No transport during identity capture"); }
        });
    }
    @Test void activeAdapterAndBothBoundsChangeTheFrozenProviderIdentity() {
        var legacy = config.captureProviderBinding("OPENAI");
        budget.set(new ProviderRateQuotas.TokenBudget(OpenAiResponsesTokenContract.ADAPTER, 5000, 256));
        var active = config.captureProviderBinding("OPENAI"); assertThat(active).isNotEqualTo(legacy);
        try (var scope = config.withRequestProvider("OPENAI", active)) { assertThat(config.getActiveProviderId()).isEqualTo(LlmProvider.OPENAI.id()); }
        for (var changed : List.of(new ProviderRateQuotas.TokenBudget(OpenAiResponsesTokenContract.ADAPTER, 5001, 256),
                new ProviderRateQuotas.TokenBudget(OpenAiResponsesTokenContract.ADAPTER, 5000, 257))) {
            budget.set(changed);
            assertThatThrownBy(() -> config.withRequestProvider("OPENAI", active)).hasMessage("PROVIDER_CONFIGURATION_CHANGED");
        }
        budget.set(null); assertThat(config.captureProviderBinding("OPENAI")).isEqualTo(legacy);
    }
    @Test void continuationPolicyAlsoChangesForTheBuiltinProviderAndReturnsExactlyToTpmOffIdentity() {
        var prompts = mock(PromptTemplateService.class); when(prompts.getAllTemplateCodes()).thenReturn(List.of());
        var service = new LlmService(config, null, new tools.jackson.databind.json.JsonMapper(), null, prompts, null, null);
        String old = service.recoveryPolicyFingerprint("OPENAI");
        budget.set(new ProviderRateQuotas.TokenBudget(OpenAiResponsesTokenContract.ADAPTER, 5000, 256));
        String enabled = service.recoveryPolicyFingerprint("OPENAI"); assertThat(enabled).isNotEqualTo(old);
        budget.set(new ProviderRateQuotas.TokenBudget(OpenAiResponsesTokenContract.ADAPTER, 5000, 257));
        assertThat(service.recoveryPolicyFingerprint("OPENAI")).isNotEqualTo(enabled);
        budget.set(null); assertThat(service.recoveryPolicyFingerprint("OPENAI")).isEqualTo(old);
    }
    @Test void anUnsupportedAdapterCannotProduceAReusableFrozenBinding() {
        budget.set(new ProviderRateQuotas.TokenBudget("unproven", 5000, 256));
        assertThatThrownBy(() -> config.captureProviderBinding("OPENAI")).hasMessage("PROVIDER_PLUGIN_UNAVAILABLE");
    }
    @Test void mockContinuationDoesNotAcquireAnUnrelatedPhysicalProviderTokenContract() {
        ReflectionTestUtils.setField(config, "llmMock", true);
        var prompts = mock(PromptTemplateService.class); when(prompts.getAllTemplateCodes()).thenReturn(List.of());
        var service = new LlmService(config, null, new tools.jackson.databind.json.JsonMapper(), null, prompts, null, null);
        String old = service.recoveryPolicyFingerprint("MOCK");
        budget.set(new ProviderRateQuotas.TokenBudget(OpenAiResponsesTokenContract.ADAPTER, 5000, 256));
        assertThatCode(() -> assertThat(service.recoveryPolicyFingerprint("MOCK")).isEqualTo(old))
                .as("MOCK has no physical model/token contract and must keep its old identity").doesNotThrowAnyException();
    }
}
