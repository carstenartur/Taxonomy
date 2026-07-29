package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit tests for {@link LlmGatewayRegistry}. */
class LlmGatewayRegistryTest {

    private LlmGatewayRegistry registry;

    @BeforeEach
    void setUp() {
        LlmProviderConfig providerConfig = mock(LlmProviderConfig.class);
        when(providerConfig.getGeminiUrl()).thenReturn("https://gemini.test/v1/generate?key=");
        when(providerConfig.getOpenAiCompatibleUrl(LlmProvider.CUSTOM_OPENAI))
                .thenReturn("http://custom-llm.test/v1/chat/completions");
        when(providerConfig.getOpenAiCompatibleModel(LlmProvider.CUSTOM_OPENAI))
                .thenReturn("custom-model");

        RestTemplate restTemplate = mock(RestTemplate.class);
        ObjectMapper objectMapper = JsonMapper.builder().build();
        PreferencesService preferencesService = mock(PreferencesService.class);
        SimpleClientHttpRequestFactory requestFactory = mock(SimpleClientHttpRequestFactory.class);
        LlmRecordReplayService replayService = mock(LlmRecordReplayService.class);

        registry = new LlmGatewayRegistry(providerConfig, restTemplate, objectMapper,
                preferencesService, requestFactory, replayService);
    }

    @Test
    void gemini_returnsGeminiGateway() {
        LlmGateway gw = registry.getGateway(LlmProvider.GEMINI);
        assertThat(gw).isInstanceOf(GeminiGateway.class);
        assertThat(gw.providerName()).isEqualTo("GEMINI");
    }

    @Test
    void openai_returnsOpenAiCompatibleGateway() {
        LlmGateway gw = registry.getGateway(LlmProvider.OPENAI);
        assertThat(gw).isInstanceOf(OpenAiCompatibleGateway.class);
        assertThat(gw.providerName()).isEqualTo("OPENAI");
    }

    @Test
    void deepseek_returnsOpenAiCompatibleGateway() {
        LlmGateway gw = registry.getGateway(LlmProvider.DEEPSEEK);
        assertThat(gw).isInstanceOf(OpenAiCompatibleGateway.class);
        assertThat(gw.providerName()).isEqualTo("DEEPSEEK");
    }

    @Test
    void qwen_returnsOpenAiCompatibleGateway() {
        LlmGateway gw = registry.getGateway(LlmProvider.QWEN);
        assertThat(gw).isInstanceOf(OpenAiCompatibleGateway.class);
        assertThat(gw.providerName()).isEqualTo("QWEN");
    }

    @Test
    void llama_returnsOpenAiCompatibleGateway() {
        LlmGateway gw = registry.getGateway(LlmProvider.LLAMA);
        assertThat(gw).isInstanceOf(OpenAiCompatibleGateway.class);
        assertThat(gw.providerName()).isEqualTo("LLAMA");
    }

    @Test
    void mistral_returnsOpenAiCompatibleGateway() {
        LlmGateway gw = registry.getGateway(LlmProvider.MISTRAL);
        assertThat(gw).isInstanceOf(OpenAiCompatibleGateway.class);
        assertThat(gw.providerName()).isEqualTo("MISTRAL");
    }

    @Test
    void customOpenAi_returnsOpenAiCompatibleGateway() {
        LlmGateway gw = registry.getGateway(LlmProvider.CUSTOM_OPENAI);
        assertThat(gw).isInstanceOf(OpenAiCompatibleGateway.class);
        assertThat(gw.providerName()).isEqualTo("CUSTOM_OPENAI");
    }

    @Test
    void localOnnx_throwsIllegalArgument() {
        assertThatThrownBy(() -> registry.getGateway(LlmProvider.LOCAL_ONNX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOCAL_ONNX");
    }

    @Test
    void eachProvider_hasIndependentGatewayInstance() {
        LlmGateway gemini = registry.getGateway(LlmProvider.GEMINI);
        LlmGateway openai = registry.getGateway(LlmProvider.OPENAI);
        LlmGateway deepseek = registry.getGateway(LlmProvider.DEEPSEEK);
        LlmGateway custom = registry.getGateway(LlmProvider.CUSTOM_OPENAI);

        assertThat(gemini).isNotSameAs(openai);
        assertThat(gemini).isNotSameAs(deepseek);
        assertThat(gemini).isNotSameAs(custom);
        assertThat(openai).isNotSameAs(deepseek);
        assertThat(openai).isNotSameAs(custom);
        assertThat(deepseek).isNotSameAs(custom);
    }
}
