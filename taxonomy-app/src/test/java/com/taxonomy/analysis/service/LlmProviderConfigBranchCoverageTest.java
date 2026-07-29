package com.taxonomy.analysis.service;

import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmProviderConfigBranchCoverageTest {

    @Mock private LocalEmbeddingService localEmbeddingService;
    private LlmProviderConfig config;

    @BeforeEach
    void setUp() {
        config = new LlmProviderConfig(localEmbeddingService);
        config.clearRequestProvider();
        set("llmProviderConfig", "");
        set("llmMock", false);
        set("geminiApiKey", "");
        set("openaiApiKey", "");
        set("deepseekApiKey", "");
        set("qwenApiKey", "");
        set("llamaApiKey", "");
        set("mistralApiKey", "");
        set("customLlmUrl", "");
        set("customLlmModel", "");
        set("customLlmApiKey", "");
    }

    @Test
    void explicitAndRequestProvidersOverrideAutoDetection() {
        set("llmProviderConfig", " custom_openai ");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.CUSTOM_OPENAI);

        config.setRequestProvider(LlmProvider.QWEN);
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.QWEN);
        config.clearRequestProvider();
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.CUSTOM_OPENAI);

        set("llmProviderConfig", "unknown-provider");
        set("deepseekApiKey", "deep-key");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.DEEPSEEK);
    }

    @Test
    void autoDetectionHonoursDocumentedPriorityAndCustomFallback() {
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.GEMINI);

        set("customLlmUrl", "http://llm-server:11434/v1/chat/completions");
        set("customLlmModel", "qwen2.5:7b");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.CUSTOM_OPENAI);

        set("mistralApiKey", "m");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.MISTRAL);
        set("llamaApiKey", "l");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.LLAMA);
        set("qwenApiKey", "q");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.QWEN);
        set("deepseekApiKey", "d");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.DEEPSEEK);
        set("openaiApiKey", "o");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.OPENAI);
        set("geminiApiKey", "g");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.GEMINI);
    }

    @Test
    void exposesProviderNamesKeysUrlsModelsAndAvailableProviders() {
        set("geminiApiKey", "g");
        set("openaiApiKey", "o");
        set("deepseekApiKey", "d");
        set("qwenApiKey", "q");
        set("llamaApiKey", "l");
        set("mistralApiKey", "m");
        set("customLlmUrl", "http://llm-server:8080/v1/chat/completions");
        set("customLlmModel", "local-model");

        assertThat(config.getAvailableProviders()).containsExactly(
                "LOCAL_ONNX", "GEMINI", "OPENAI", "DEEPSEEK", "QWEN", "LLAMA", "MISTRAL",
                "CUSTOM_OPENAI");
        assertThat(config.hasAnyCloudApiKey()).isTrue();
        assertThat(config.hasAnyHttpProviderConfiguration()).isTrue();
        assertThat(config.getApiKey(LlmProvider.GEMINI)).isEqualTo("g");
        assertThat(config.getApiKey(LlmProvider.OPENAI)).isEqualTo("o");
        assertThat(config.getApiKey(LlmProvider.DEEPSEEK)).isEqualTo("d");
        assertThat(config.getApiKey(LlmProvider.QWEN)).isEqualTo("q");
        assertThat(config.getApiKey(LlmProvider.LLAMA)).isEqualTo("l");
        assertThat(config.getApiKey(LlmProvider.MISTRAL)).isEqualTo("m");
        assertThat(config.getApiKey(LlmProvider.CUSTOM_OPENAI))
                .isEqualTo(LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY);
        assertThat(config.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).isFalse();
        assertThat(config.getApiKey(LlmProvider.LOCAL_ONNX)).isNull();
        assertThat(config.hasConfiguredApiKey(LlmProvider.LOCAL_ONNX)).isFalse();
        assertThat(config.getGeminiUrl()).contains("generativelanguage.googleapis.com");

        set("customLlmApiKey", "custom-secret");
        assertThat(config.getApiKey(LlmProvider.CUSTOM_OPENAI)).isEqualTo("custom-secret");
        assertThat(config.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).isTrue();

        List<LlmProvider> fixedCloudProviders = List.of(
                LlmProvider.OPENAI, LlmProvider.DEEPSEEK, LlmProvider.QWEN,
                LlmProvider.LLAMA, LlmProvider.MISTRAL);
        for (LlmProvider provider : fixedCloudProviders) {
            assertThat(config.getOpenAiCompatibleUrl(provider)).startsWith("https://");
            assertThat(config.getOpenAiCompatibleModel(provider)).isNotBlank();
            config.setRequestProvider(provider);
            assertThat(config.getActiveProviderName()).isNotBlank();
            assertThat(config.isProviderConfigured(provider)).isTrue();
            assertThat(config.hasConfiguredApiKey(provider)).isTrue();
        }

        assertThat(config.getOpenAiCompatibleUrl(LlmProvider.CUSTOM_OPENAI))
                .isEqualTo("http://llm-server:8080/v1/chat/completions");
        assertThat(config.getOpenAiCompatibleModel(LlmProvider.CUSTOM_OPENAI))
                .isEqualTo("local-model");
        assertThat(config.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).isTrue();

        config.setRequestProvider(LlmProvider.CUSTOM_OPENAI);
        assertThat(config.getActiveProviderName()).isEqualTo("Custom OpenAI-compatible");
        config.setRequestProvider(LlmProvider.GEMINI);
        assertThat(config.getActiveProviderName()).isEqualTo("Gemini");
        config.setRequestProvider(LlmProvider.LOCAL_ONNX);
        assertThat(config.getActiveProviderName()).contains("Local");
        config.clearRequestProvider();

        assertThatThrownBy(() -> config.getOpenAiCompatibleUrl(LlmProvider.GEMINI))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.getOpenAiCompatibleModel(LlmProvider.LOCAL_ONNX))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void customProviderRequiresCompleteSafeChatCompletionsUrlAndModelButNotApiKey() {
        set("llmProviderConfig", "CUSTOM_OPENAI");
        when(localEmbeddingService.isAvailable()).thenReturn(false);

        set("customLlmUrl", "not-a-url");
        set("customLlmModel", "model");
        assertThat(config.isCustomOpenAiConfigured()).isFalse();
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.UNAVAILABLE);
        assertThat(config.getApiKey(LlmProvider.CUSTOM_OPENAI)).isEmpty();

        set("customLlmUrl", "http://localhost:11434");
        assertThat(config.isCustomOpenAiConfigured()).isFalse();

        set("customLlmUrl", "http://localhost:11434/v1/models");
        assertThat(config.isCustomOpenAiConfigured()).isFalse();

        set("customLlmUrl", "http://user:secret@localhost:11434/v1/chat/completions");
        assertThat(config.isCustomOpenAiConfigured()).isFalse();

        set("customLlmUrl", "http://localhost:11434/v1/chat/completions");
        set("customLlmModel", " ");
        assertThat(config.isCustomOpenAiConfigured()).isFalse();

        set("customLlmModel", "llama3.2");
        assertThat(config.isCustomOpenAiConfigured()).isTrue();
        assertThat(config.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).isTrue();
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.FULL);
        assertThat(config.getApiKey(LlmProvider.CUSTOM_OPENAI))
                .isEqualTo(LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY);
        assertThat(config.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).isFalse();
    }

    @Test
    void availabilityDistinguishesMockConfiguredLocalAndUnavailableModes() {
        when(localEmbeddingService.isAvailable()).thenReturn(false);
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.UNAVAILABLE);
        assertThat(config.isAvailable()).isFalse();

        when(localEmbeddingService.isAvailable()).thenReturn(true);
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.LIMITED);
        assertThat(config.isAvailable()).isTrue();

        config.setRequestProvider(LlmProvider.LOCAL_ONNX);
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.LIMITED);
        config.clearRequestProvider();

        set("openaiApiKey", "key");
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.FULL);

        set("llmMock", true);
        assertThat(config.isMockMode()).isTrue();
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.FULL);
        assertThat(config.getActiveProviderName()).isEqualTo("Mock");
    }

    private void set(String field, Object value) {
        ReflectionTestUtils.setField(config, field, value);
    }
}
