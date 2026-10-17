package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomLlmCapacityConfigurationTest {
    @ParameterizedTest @CsvSource({
            "-1,4096,INVALID_CONTEXT_WINDOW,CUSTOM_LLM_CONTEXT_WINDOW_TOKENS",
            "2147483647,4096,INVALID_CONTEXT_WINDOW,CUSTOM_LLM_CONTEXT_WINDOW_TOKENS",
            "0,0,INVALID_MAX_OUTPUT_TOKENS,CUSTOM_LLM_MAX_OUTPUT_TOKENS",
            "0,-1,INVALID_MAX_OUTPUT_TOKENS,CUSTOM_LLM_MAX_OUTPUT_TOKENS",
            "0,1000001,INVALID_MAX_OUTPUT_TOKENS,CUSTOM_LLM_MAX_OUTPUT_TOKENS",
            "4096,4096,OUTPUT_RESERVATION_EXHAUSTS_CONTEXT,CUSTOM_LLM_MAX_OUTPUT_TOKENS",
            "2048,4096,OUTPUT_RESERVATION_EXHAUSTS_CONTEXT,CUSTOM_LLM_CONTEXT_WINDOW_TOKENS"})
    void invalidCapacityHasAnActionableCustomConfigurationStatus(int context, int output, String code, String property) {
        LlmProviderConfig config = config(context, output);

        var status = config.getCustomOpenAiConfigurationStatus();
        assertThat(status.valid()).isFalse();
        assertThat(status.code()).isEqualTo(code);
        assertThat(status.message()).contains(property);
        assertThat(config.isCustomOpenAiConfigured()).isFalse();
        assertThat(config.getProviderConfigurationError(LlmProvider.CUSTOM_OPENAI)).isEqualTo(status.message());
    }

    @ParameterizedTest @CsvSource({"0,4096", "8192,2048", "1000000000,1000000"})
    void unknownAndValidKnownCapacityRemainAvailable(int context, int output) {
        assertThat(config(context, output).getCustomOpenAiConfigurationStatus().valid()).isTrue();
    }

    @ParameterizedTest @CsvSource({"-1,4096", "0,0", "4096,4096", "2147483647,4096"})
    void explicitCustomSelectionFailsBeforeAnyHttpWhenItsCapacityIsInvalid(int context, int output) {
        LlmProviderConfig config = config(context, output);
        var transport = mock(RestTemplate.class);
        var registry = new LlmGatewayRegistry(config, transport, JsonMapper.builder().build(), null, null, null);

        assertThatThrownBy(() -> registry.getGateway(LlmProvider.CUSTOM_OPENAI)
                .sendHttpRequest("prompt", "", OpenAiCompletionOptionsTest.options(128)))
                .isInstanceOfSatisfying(LlmProviderException.class,
                        failure -> assertThat(failure.getReason()).isEqualTo(LlmProviderException.Reason.CONFIGURATION));
        verifyNoInteractions(transport);
    }

    private static LlmProviderConfig config(int context, int output) {
        var config = new LlmProviderConfig(mock(LocalEmbeddingService.class));
        ReflectionTestUtils.setField(config, "llmProviderConfig", "CUSTOM_OPENAI");
        ReflectionTestUtils.setField(config, "customLlmUrl", "http://localhost:8000/v1/chat/completions");
        ReflectionTestUtils.setField(config, "customLlmModel", "fixture-model");
        ReflectionTestUtils.setField(config, "customLlmContextWindowTokens", context);
        ReflectionTestUtils.setField(config, "customLlmMaxOutputTokens", output);
        return config;
    }
}
