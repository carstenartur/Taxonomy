package com.taxonomy.analysis.service;

import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmGatewayRegistryPromptBudgetTest {

    @Test
    void productionConstructorWrapsEveryRemoteGatewayAtTheFinalPromptBoundary() {
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
        AiPromptBudgetPolicy promptBudgetPolicy = mock(AiPromptBudgetPolicy.class);

        LlmGatewayRegistry registry = new LlmGatewayRegistry(
                providerConfig,
                restTemplate,
                objectMapper,
                preferencesService,
                requestFactory,
                replayService,
                promptBudgetPolicy);

        for (LlmProvider provider : List.of(
                LlmProvider.GEMINI,
                LlmProvider.OPENAI,
                LlmProvider.DEEPSEEK,
                LlmProvider.QWEN,
                LlmProvider.LLAMA,
                LlmProvider.MISTRAL,
                LlmProvider.CUSTOM_OPENAI)) {
            LlmGateway gateway = registry.getGateway(provider);
            assertThat(gateway)
                    .as("final prompt boundary for %s", provider)
                    .isInstanceOf(PromptBudgetEnforcingLlmGateway.class);
            PromptBudgetEnforcingLlmGateway enforcing =
                    (PromptBudgetEnforcingLlmGateway) gateway;
            assertThat(enforcing.delegate().providerName()).isEqualTo(provider.name());
        }
    }
}
