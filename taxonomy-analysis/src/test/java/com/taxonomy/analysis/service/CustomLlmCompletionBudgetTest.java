package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CustomLlmCompletionBudgetTest {
    @Test void unknownContextRetainsTheGeneralPromptBudgetAndDefaults() {
        LlmProviderConfig providers = customConfig();
        assertThat(providers.getCustomLlmContextWindowTokens()).isZero();
        assertThat(providers.getCustomLlmMaxOutputTokens()).isEqualTo(4096);
        assertThat(providers.isCustomLlmStructuredOutputEnabled()).isTrue();
        assertThat(new AiTargetCatalogService(providers).describeProvider("CUSTOM_OPENAI")
                .promptBudget().maxInputCharacters()).isEqualTo(120_000);
    }

    @Test void knownContextReservesConfiguredOutputBeforeEstimatingThePromptBudget() {
        LlmProviderConfig providers = customConfig();
        ReflectionTestUtils.setField(providers, "customLlmContextWindowTokens", 8192);
        ReflectionTestUtils.setField(providers, "customLlmMaxOutputTokens", 2048);

        var target = new AiTargetCatalogService(providers).describeProvider("CUSTOM_OPENAI");
        assertThat(target.promptBudget().estimatedMaxInputTokens()).isEqualTo(6144);
        assertThat(target.promptBudget().maxInputCharacters()).isEqualTo(24_576);
        assertThatThrownBy(() -> new AiPromptBudgetPolicy(new AiTargetCatalogService(providers))
                .requireWithinBudget("a".repeat(24_577), "CUSTOM_OPENAI"))
                .isInstanceOf(PromptBudgetExceededException.class);
    }

    @Test void changingTheOutputReservationChangesTheTargetIdentity() {
        LlmProviderConfig providers = customConfig();
        ReflectionTestUtils.setField(providers, "customLlmContextWindowTokens", 8192);
        var catalog = new AiTargetCatalogService(providers);
        String before = catalog.describeProvider("CUSTOM_OPENAI").configurationFingerprint();
        ReflectionTestUtils.setField(providers, "customLlmMaxOutputTokens", 2048);
        assertThat(catalog.describeProvider("CUSTOM_OPENAI").configurationFingerprint()).isNotEqualTo(before);
    }

    @Test void customCompletionChangesInvalidateBothFrozenBindingsAndRecoveryIdentity() {
        LlmProviderConfig providers = customConfig();
        installCustomPlugin(providers);
        var prompts = mock(PromptTemplateService.class);
        org.mockito.Mockito.when(prompts.getAllTemplateCodes()).thenReturn(java.util.List.of());
        var llm = new LlmService(providers, null, tools.jackson.databind.json.JsonMapper.builder().build(), null, prompts, null, null);
        var before = providers.captureProviderBinding("CUSTOM_OPENAI");
        String recoveryBefore = llm.recoveryPolicyFingerprint("CUSTOM_OPENAI");

        for (var change : java.util.Map.<String, Object>of("customLlmContextWindowTokens", 8192,
                "customLlmMaxOutputTokens", 2048, "customLlmStructuredOutputEnabled", false).entrySet()) {
            ReflectionTestUtils.setField(providers, change.getKey(), change.getValue());
            assertThat(providers.captureProviderBinding("CUSTOM_OPENAI")).isNotEqualTo(before);
            assertThat(llm.recoveryPolicyFingerprint("CUSTOM_OPENAI")).isNotEqualTo(recoveryBefore);
            assertThatThrownBy(() -> providers.withRequestProvider("CUSTOM_OPENAI", before))
                    .hasMessage("PROVIDER_CONFIGURATION_CHANGED");
            ReflectionTestUtils.setField(providers, "customLlmContextWindowTokens", 0);
            ReflectionTestUtils.setField(providers, "customLlmMaxOutputTokens", 4096);
            ReflectionTestUtils.setField(providers, "customLlmStructuredOutputEnabled", true);
        }
    }

    private static void installCustomPlugin(LlmProviderConfig providers) {
        var catalog = new com.taxonomy.extension.runtime.PluginCatalog();
        var extension = new com.taxonomy.extension.api.llm.LlmProviderExtension() {
            public com.taxonomy.extension.api.llm.LlmProviderDescriptor descriptor() {
                return new com.taxonomy.extension.api.llm.LlmProviderDescriptor("CUSTOM_OPENAI", "Custom", false, false,
                        false, true, java.util.List.of());
            }
        };
        catalog.publish(new com.taxonomy.extension.api.plugin.PluginDescriptor(
                new com.taxonomy.extension.api.plugin.PluginIdentity("builtin.custom-openai", "1.0.0", "e".repeat(64)),
                ">=1.0.0", java.util.List.of(), java.util.Set.of(), com.taxonomy.extension.api.plugin.PluginMode.STARTUP),
                java.util.List.of(extension));
        ReflectionTestUtils.setField(providers, "catalog", catalog);
    }

    private static LlmProviderConfig customConfig() {
        var providers = new LlmProviderConfig(mock(LocalEmbeddingService.class));
        ReflectionTestUtils.setField(providers, "customLlmUrl", "http://localhost:8000/v1/chat/completions");
        ReflectionTestUtils.setField(providers, "customLlmModel", "fixture-model");
        return providers;
    }
}
