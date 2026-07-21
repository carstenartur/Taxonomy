package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProviderExtensionRegistryTest {

    private LlmProviderExtensionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new LlmProviderExtensionRegistry(List.of(
                new GeminiLlmProviderExtension(),
                new OpenAiLlmProviderExtension(),
                new DeepSeekLlmProviderExtension(),
                new QwenLlmProviderExtension(),
                new LlamaLlmProviderExtension(),
                new MistralLlmProviderExtension(),
                new LocalOnnxLlmProviderExtension()));
    }

    @Test
    void listDescriptorsReturnsEveryRuntimeProvider() {
        List<LlmProviderDescriptor> descriptors = registry.listDescriptors();
        assertThat(descriptors).hasSize(LlmProvider.values().length);
        assertThat(descriptors)
                .extracting(LlmProviderDescriptor::providerId)
                .containsExactlyInAnyOrder(
                        Arrays.stream(LlmProvider.values())
                                .map(LlmProvider::name)
                                .toArray(String[]::new));
    }

    @ParameterizedTest
    @EnumSource(LlmProvider.class)
    void eachRuntimeProviderResolvesToMatchingDescriptor(LlmProvider provider) {
        LlmProviderExtension extension = registry.getRequired(provider);
        assertThat(extension.descriptor().providerId()).isEqualTo(provider.name());
    }

    @Test
    void findByIdIsCaseInsensitiveAndHandlesUnknownValues() {
        assertThat(registry.findById("gemini")).isPresent();
        assertThat(registry.findById("GEMINI")).isPresent();
        assertThat(registry.findById(" Gemini ")).isPresent();
        assertThat(registry.findById("unknown")).isEmpty();
        assertThat(registry.findById(null)).isEmpty();
        assertThat(registry.findById("  ")).isEmpty();
    }

    @Test
    void getRequiredRejectsNullProvider() {
        assertThatThrownBy(() -> registry.getRequired(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void capabilityMetadataDistinguishesLocalAndCloudProviders() {
        LlmProviderDescriptor local = registry.getRequired(LlmProvider.LOCAL_ONNX).descriptor();
        assertThat(local.requiresApiKey()).isFalse();
        assertThat(local.supportsLocalExecution()).isTrue();

        for (LlmProvider provider : LlmProvider.values()) {
            LlmProviderDescriptor descriptor = registry.getRequired(provider).descriptor();
            assertThat(descriptor.providerId()).isNotBlank();
            assertThat(descriptor.providerName()).isNotBlank();
            assertThat(descriptor.configurationProperties()).isNotNull();
            if (provider != LlmProvider.LOCAL_ONNX) {
                assertThat(descriptor.requiresApiKey())
                        .as("Provider %s should require an API key", provider)
                        .isTrue();
                assertThat(descriptor.supportsLocalExecution()).isFalse();
            }
        }
    }

    @Test
    void duplicateAndUnknownDescriptorIdsFailFast() {
        assertThatThrownBy(() -> new LlmProviderExtensionRegistry(List.of(
                new GeminiLlmProviderExtension(),
                new GeminiLlmProviderExtension())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate LLM provider ID");

        LlmProviderExtension unknown = () -> new LlmProviderDescriptor(
                "NOT_A_PROVIDER", "Unknown", false, false, false, false, List.of());
        assertThatThrownBy(() -> new LlmProviderExtensionRegistry(List.of(unknown)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown runtime provider ID");
    }
}
