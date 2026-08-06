package com.taxonomy.analysis.service;

import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomOpenAiGatewayDiagnosticsTest {

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private LlmResponseParser responseParser;
    private PreferencesService preferencesService;
    private SimpleClientHttpRequestFactory requestFactory;
    private LlmRecordReplayService replayService;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        objectMapper = JsonMapper.builder().build();
        responseParser = new LlmResponseParser(objectMapper);
        preferencesService = mock(PreferencesService.class);
        requestFactory = mock(SimpleClientHttpRequestFactory.class);
        replayService = mock(LlmRecordReplayService.class);
    }

    @Test
    void invalidConfigurationFailsBeforeAnyNetworkCall() {
        OpenAiCompatibleGateway gateway = gateway("http://llm-service:8000/v1/models", "model");

        assertThatThrownBy(() -> gateway.sendHttpRequest(
                "prompt", LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("CUSTOM_LLM_URL")
                .hasMessageContaining("/chat/completions")
                .hasMessageNotContaining("CUSTOM_OPENAI_API_KEY");

        verifyNoInteractions(restTemplate);
    }

    @SuppressWarnings("unchecked")
    @Test
    void authenticationRejectionMentionsOnlyTheOptionalActualVariable() {
        OpenAiCompatibleGateway gateway = gateway(
                "http://llm-service:8000/v1/chat/completions", "model");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY,
                        new byte[0], null));

        assertThatThrownBy(() -> gateway.sendHttpRequest("prompt", "wrong-token"))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("rejected authentication")
                .hasMessageContaining("CUSTOM_LLM_API_KEY")
                .hasMessageNotContaining("CUSTOM_OPENAI_API_KEY");
    }

    @SuppressWarnings("unchecked")
    @Test
    void unreachableEndpointProducesAnActionableNetworkDiagnostic() {
        OpenAiCompatibleGateway gateway = gateway(
                "http://llm-service:8000/v1/chat/completions", "model");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> gateway.sendHttpRequest(
                "prompt", LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY))
                .isInstanceOf(LlmProviderException.class)
                .hasMessageContaining("endpoint is unreachable")
                .hasMessageContaining("CUSTOM_LLM_URL");
    }

    private OpenAiCompatibleGateway gateway(String url, String model) {
        return new OpenAiCompatibleGateway(
                LlmProvider.CUSTOM_OPENAI, url, model, 0,
                restTemplate, objectMapper, responseParser,
                preferencesService, requestFactory, replayService);
    }
}
