package com.taxonomy.analysis.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link LlmProviderExtensionRegistry} and the bundled provider extension adapters.
 */
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
                new LocalOnnxLlmProviderExtension()
        ));
    }

    @Test
    void listDescriptors_returnsAllProviders() {
        List<LlmProviderDescriptor> descriptors = registry.listDescriptors();
        assertThat(descriptors).hasSize(LlmProvider.values().length);
        assertThat(descriptors)
                .extracting(LlmProviderDescriptor::providerId)
                .containsExactlyInAnyOrder(
                        java.util.Arrays.stream(LlmProvider.values())
                                .map(LlmProvider::name)
                                .toArray(String[]::new));
    }

    @ParameterizedTest
    @EnumSource(LlmProvider.class)
    void eachProviderEnum_resolvesToRegisteredExtension(LlmProvider provider) {
        LlmProviderExtension ext = registry.getRequired(provider);
        assertThat(ext).isNotNull();
        assertThat(ext.provider()).isEqualTo(provider);
        assertThat(ext.descriptor().providerId()).isEqualTo(provider.name());
    }

    @Test
    void findById_caseInsensitive() {
        assertThat(registry.findById("gemini")).isPresent();
        assertThat(registry.findById("GEMINI")).isPresent();
        assertThat(registry.findById("Gemini")).isPresent();
    }

    @Test
    void findById_unknownReturnsEmpty() {
        assertThat(registry.findById("unknown")).isEmpty();
        assertThat(registry.findById(null)).isEmpty();
        assertThat(registry.findById("  ")).isEmpty();
    }

    @Test
    void getRequired_nullProviderThrows() {
        assertThatThrownBy(() -> registry.getRequired(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void localOnnx_doesNotRequireApiKey() {
        LlmProviderDescriptor desc = registry.getRequired(LlmProvider.LOCAL_ONNX).descriptor();
        assertThat(desc.requiresApiKey()).isFalse();
        assertThat(desc.supportsLocalExecution()).isTrue();
    }

    @Test
    void cloudProviders_requireApiKey() {
        for (LlmProvider provider : LlmProvider.values()) {
            if (provider == LlmProvider.LOCAL_ONNX) continue;
            LlmProviderDescriptor desc = registry.getRequired(provider).descriptor();
            assertThat(desc.requiresApiKey())
                    .as("Provider %s should require an API key", provider)
                    .isTrue();
            assertThat(desc.supportsLocalExecution())
                    .as("Cloud provider %s should not support local execution", provider)
                    .isFalse();
        }
    }

    @Test
    void allDescriptors_haveNonBlankProviderIdAndName() {
        for (LlmProviderDescriptor desc : registry.listDescriptors()) {
            assertThat(desc.providerId()).isNotBlank();
            assertThat(desc.providerName()).isNotBlank();
        }
    }

    @Test
    void allDescriptors_haveNonNullConfigurationProperties() {
        for (LlmProviderDescriptor desc : registry.listDescriptors()) {
            assertThat(desc.configurationProperties()).isNotNull();
        }
    }

    @Test
    void duplicateProviderIdThrowsOnRegistryCreation() {
        List<LlmProviderExtension> withDuplicate = List.of(
                new GeminiLlmProviderExtension(),
                new GeminiLlmProviderExtension()
        );
        assertThatThrownBy(() -> new LlmProviderExtensionRegistry(withDuplicate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate LLM provider ID");
    }
}
