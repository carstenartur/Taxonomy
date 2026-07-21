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
        set("llmProviderConfig", "");
        set("llmMock", false);
        set("geminiApiKey", "");
        set("openaiApiKey", "");
        set("deepseekApiKey", "");
        set("qwenApiKey", "");
        set("llamaApiKey", "");
        set("mistralApiKey", "");
    }

    @Test
    void explicitAndRequestProvidersOverrideAutoDetection() {
        set("llmProviderConfig", " mistral ");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.MISTRAL);

        config.setRequestProvider(LlmProvider.QWEN);
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.QWEN);
        config.clearRequestProvider();
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.MISTRAL);

        set("llmProviderConfig", "unknown-provider");
        set("deepseekApiKey", "deep-key");
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.DEEPSEEK);
    }

    @Test
    void autoDetectionHonoursDocumentedKeyPriorityAndDefault() {
        assertThat(config.getActiveProvider()).isEqualTo(LlmProvider.GEMINI);

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

        assertThat(config.getAvailableProviders()).containsExactly(
                "LOCAL_ONNX", "GEMINI", "OPENAI", "DEEPSEEK", "QWEN", "LLAMA", "MISTRAL");
        assertThat(config.hasAnyCloudApiKey()).isTrue();
        assertThat(config.getApiKey(LlmProvider.GEMINI)).isEqualTo("g");
        assertThat(config.getApiKey(LlmProvider.OPENAI)).isEqualTo("o");
        assertThat(config.getApiKey(LlmProvider.DEEPSEEK)).isEqualTo("d");
        assertThat(config.getApiKey(LlmProvider.QWEN)).isEqualTo("q");
        assertThat(config.getApiKey(LlmProvider.LLAMA)).isEqualTo("l");
        assertThat(config.getApiKey(LlmProvider.MISTRAL)).isEqualTo("m");
        assertThat(config.getApiKey(LlmProvider.LOCAL_ONNX)).isNull();
        assertThat(config.getGeminiUrl()).contains("generativelanguage.googleapis.com");

        for (LlmProvider provider : List.of(LlmProvider.OPENAI, LlmProvider.DEEPSEEK,
                LlmProvider.QWEN, LlmProvider.LLAMA, LlmProvider.MISTRAL)) {
            assertThat(config.getOpenAiCompatibleUrl(provider)).startsWith("https://");
            assertThat(config.getOpenAiCompatibleModel(provider)).isNotBlank();
            config.setRequestProvider(provider);
            assertThat(config.getActiveProviderName()).isNotBlank();
        }
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
    void availabilityDistinguishesMockCloudLocalAndUnavailableModes() {
        when(localEmbeddingService.isAvailable()).thenReturn(false, true, false, true);

        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.UNAVAILABLE);
        assertThat(config.isAvailable()).isTrue();

        config.setRequestProvider(LlmProvider.LOCAL_ONNX);
        assertThat(config.getAvailabilityLevel()).isEqualTo(AiAvailabilityLevel.UNAVAILABLE);
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
