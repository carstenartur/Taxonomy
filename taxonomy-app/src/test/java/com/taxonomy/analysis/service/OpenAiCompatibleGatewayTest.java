package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.taxonomy.preferences.PreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link OpenAiCompatibleGateway}.
 */
class OpenAiCompatibleGatewayTest {

    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private LlmResponseParser responseParser;
    private PreferencesService preferencesService;
    private SimpleClientHttpRequestFactory requestFactory;
    private LlmRecordReplayService replayService;
    private OpenAiCompatibleGateway gateway;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        objectMapper = JsonMapper.builder().build();
        responseParser = new LlmResponseParser(objectMapper);
        preferencesService = mock(PreferencesService.class);
        requestFactory = mock(SimpleClientHttpRequestFactory.class);
        replayService = mock(LlmRecordReplayService.class);

        gateway = new OpenAiCompatibleGateway(
                LlmProvider.OPENAI, "https://api.openai.com/v1/chat/completions",
                "gpt-4o-mini", 60,
                restTemplate, objectMapper, responseParser,
                preferencesService, requestFactory, replayService);
    }

    @Test
    void providerName_matchesConstructor() {
        assertThat(gateway.providerName()).isEqualTo("OPENAI");
    }

    @Test
    void extractResponseText_delegatesToOpenAiParser() {
        String openAiBody = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}";
        assertThat(gateway.extractResponseText(openAiBody)).isEqualTo("hello");
    }

    @Test
    void extractResponseText_returnsNullForInvalidBody() {
        assertThat(gateway.extractResponseText("{\"invalid\":true}")).isNull();
    }

    @Nested
    class SendHttpRequest {

        @Test
        void replayMode_returnsRecordedResponse() {
            when(replayService.isReplayMode()).thenReturn(true);
            when(replayService.replay(anyString())).thenReturn(Optional.of("{\"recorded\":true}"));

            String result = gateway.sendHttpRequest("test prompt", "api-key");

            assertThat(result).isEqualTo("{\"recorded\":true}");
            verifyNoInteractions(restTemplate);
        }

        @SuppressWarnings("unchecked")
        @Test
        void successfulCall_returnsResponseBody() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm.openai"), anyInt())).thenReturn(0);

            String responseBody = "{\"choices\":[{\"message\":{\"content\":\"scored\"}}]}";
            ResponseEntity<String> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(response);

            String result = gateway.sendHttpRequest("test prompt", "test-key");

            assertThat(result).isEqualTo(responseBody);
        }

        @SuppressWarnings("unchecked")
        @Test
        void http429_throwsRateLimitException() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm.openai"), anyInt())).thenReturn(0);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                            HttpHeaders.EMPTY, "rate limited".getBytes(), null));

            assertThatThrownBy(() -> gateway.sendHttpRequest("prompt", "key"))
                    .isInstanceOf(LlmRateLimitException.class)
                    .hasMessageContaining("429");
        }

        @SuppressWarnings("unchecked")
        @Test
        void recordMode_persistsResponse() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(replayService.isRecordMode()).thenReturn(true);
            when(preferencesService.getInt(eq("llm.rpm.openai"), anyInt())).thenReturn(0);

            String responseBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
            ResponseEntity<String> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(response);

            gateway.sendHttpRequest("prompt", "key");

            verify(replayService).record(eq("prompt"), eq(responseBody), eq("OPENAI"), isNull());
        }
    }

    @Nested
    class Throttle {

        @Test
        void noPreferencesService_noOp() {
            OpenAiCompatibleGateway gw = new OpenAiCompatibleGateway(
                    LlmProvider.DEEPSEEK, "https://deepseek.test/v1/chat", "deepseek-chat",
                    0, restTemplate, objectMapper, responseParser,
                    null, requestFactory, replayService);
            // Should not throw
            gw.throttle();
        }

        @Test
        void defaultRpmZero_noThrottle() {
            OpenAiCompatibleGateway gw = new OpenAiCompatibleGateway(
                    LlmProvider.DEEPSEEK, "https://deepseek.test/v1/chat", "deepseek-chat",
                    0, restTemplate, objectMapper, responseParser,
                    preferencesService, requestFactory, replayService);

            when(preferencesService.getInt(eq("llm.rpm.deepseek"), eq(0))).thenReturn(0);
            // Should complete instantly
            gw.throttle();
        }

        @Test
        void withinRpmLimit_noSleep() {
            when(preferencesService.getInt(eq("llm.rpm.openai"), anyInt())).thenReturn(60);
            // Should complete instantly — no calls yet
            gateway.throttle();
        }
    }

    @Nested
    class PerProviderConfiguration {

        @Test
        void deepSeekGateway_hasOwnThrottleQueue() {
            OpenAiCompatibleGateway deepSeek = new OpenAiCompatibleGateway(
                    LlmProvider.DEEPSEEK, "https://api.deepseek.com/v1/chat/completions",
                    "deepseek-chat", 0,
                    restTemplate, objectMapper, responseParser,
                    preferencesService, requestFactory, replayService);

            assertThat(deepSeek.providerName()).isEqualTo("DEEPSEEK");
        }

        @Test
        void qwenGateway_hasCorrectProviderName() {
            OpenAiCompatibleGateway qwen = new OpenAiCompatibleGateway(
                    LlmProvider.QWEN, "https://dashscope.aliyuncs.com/v1/chat/completions",
                    "qwen-plus", 0,
                    restTemplate, objectMapper, responseParser,
                    preferencesService, requestFactory, replayService);

            assertThat(qwen.providerName()).isEqualTo("QWEN");
        }
    }
}
