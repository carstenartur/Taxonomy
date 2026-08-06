package com.taxonomy.analysis.service;

import com.taxonomy.shared.service.LocalEmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CustomOpenAiConfigurationDiagnosticsTest {

    private LlmProviderConfig config;

    @BeforeEach
    void setUp() {
        config = new LlmProviderConfig(mock(LocalEmbeddingService.class));
        set("llmProviderConfig", "CUSTOM_OPENAI");
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
    void reportsMissingUrlWithoutInventingAnApiKeyRequirement() {
        var status = config.getCustomOpenAiConfigurationStatus();

        assertThat(status.valid()).isFalse();
        assertThat(status.code()).isEqualTo("MISSING_URL");
        assertThat(status.message()).contains("CUSTOM_LLM_URL");
        assertThat(status.message()).doesNotContain("CUSTOM_OPENAI_API_KEY");
        assertThat(config.getProviderConfigurationError(LlmProvider.CUSTOM_OPENAI))
                .isEqualTo(status.message());
        assertThat(config.getApiKey(LlmProvider.CUSTOM_OPENAI))
                .isEqualTo(LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY);
    }

    @Test
    void reportsEveryInvalidEndpointClassWithAnActionablePropertyName() {
        set("customLlmUrl", "http://llm-service:8000/v1/chat/completions");
        assertStatus("MISSING_MODEL", "CUSTOM_LLM_MODEL");

        set("customLlmModel", "model-id");
        set("customLlmUrl", "not a uri");
        assertStatus("INVALID_URL", "CUSTOM_LLM_URL");

        set("customLlmUrl", "ftp://llm-service/v1/chat/completions");
        assertStatus("UNSUPPORTED_SCHEME", "http or https");

        set("customLlmUrl", "http:///v1/chat/completions");
        assertStatus("MISSING_HOST", "host name");

        set("customLlmUrl", "http://user:secret@llm-service/v1/chat/completions");
        assertStatus("USER_INFO_NOT_ALLOWED", "CUSTOM_LLM_API_KEY");

        set("customLlmUrl", "http://llm-service:8000/v1/models");
        assertStatus("INVALID_PATH", "/chat/completions");
    }

    @Test
    void acceptsAuthenticatedAndUnauthenticatedCompatibleEndpoints() {
        set("customLlmUrl", "http://llm-service:8000/v1/chat/completions");
        set("customLlmModel", "exact-model-id");

        assertThat(config.getCustomOpenAiConfigurationStatus().valid()).isTrue();
        assertThat(config.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).isTrue();
        assertThat(config.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).isFalse();
        assertThat(config.getProviderConfigurationError(LlmProvider.CUSTOM_OPENAI)).isNull();

        set("customLlmApiKey", "bearer-token");
        assertThat(config.hasConfiguredApiKey(LlmProvider.CUSTOM_OPENAI)).isTrue();
        assertThat(config.getApiKey(LlmProvider.CUSTOM_OPENAI)).isEqualTo("bearer-token");
    }

    @Test
    void mapsNonCustomProvidersToTheirActualEnvironmentVariables() {
        assertThat(config.getProviderConfigurationError(LlmProvider.GEMINI))
                .isEqualTo("GEMINI_API_KEY is required.");
        assertThat(config.getProviderConfigurationError(LlmProvider.QWEN))
                .isEqualTo("DASHSCOPE_API_KEY is required.");
    }

    private void assertStatus(String code, String messagePart) {
        var status = config.getCustomOpenAiConfigurationStatus();
        assertThat(status.valid()).isFalse();
        assertThat(status.code()).isEqualTo(code);
        assertThat(status.message()).contains(messagePart);
        assertThat(status.message()).doesNotContain("CUSTOM_OPENAI_API_KEY");
    }

    private void set(String field, Object value) {
        ReflectionTestUtils.setField(config, field, value);
    }
}
