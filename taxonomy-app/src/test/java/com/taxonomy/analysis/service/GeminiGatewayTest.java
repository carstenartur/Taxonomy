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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GeminiGateway}.
 */
class GeminiGatewayTest {

    private LlmProviderConfig providerConfig;
    private RestTemplate restTemplate;
    private ObjectMapper objectMapper;
    private LlmResponseParser responseParser;
    private PreferencesService preferencesService;
    private SimpleClientHttpRequestFactory requestFactory;
    private LlmRecordReplayService replayService;
    private GeminiGateway gateway;

    @BeforeEach
    void setUp() {
        providerConfig = mock(LlmProviderConfig.class);
        restTemplate = mock(RestTemplate.class);
        objectMapper = JsonMapper.builder().build();
        responseParser = new LlmResponseParser(objectMapper);
        preferencesService = mock(PreferencesService.class);
        requestFactory = mock(SimpleClientHttpRequestFactory.class);
        replayService = mock(LlmRecordReplayService.class);

        when(providerConfig.getGeminiUrl()).thenReturn("https://gemini.test/v1/generate?key=");

        gateway = new GeminiGateway(providerConfig, restTemplate, objectMapper,
                responseParser, preferencesService, requestFactory, replayService);
    }

    @Test
    void providerName_isGemini() {
        assertThat(gateway.providerName()).isEqualTo("GEMINI");
    }

    @Test
    void extractResponseText_delegatesToGeminiParser() {
        String geminiBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}";
        assertThat(gateway.extractResponseText(geminiBody)).isEqualTo("hello");
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

        @Test
        void replayMiss_noFallback_returnsNull() {
            when(replayService.isReplayMode()).thenReturn(true);
            when(replayService.replay(anyString())).thenReturn(Optional.empty());
            when(replayService.isFallbackLive()).thenReturn(false);

            String result = gateway.sendHttpRequest("test prompt", "api-key");

            assertThat(result).isNull();
            verifyNoInteractions(restTemplate);
        }

        @SuppressWarnings("unchecked")
        @Test
        void successfulCall_returnsResponseBody() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);

            String responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"scored\"}]}}]}";
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
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);

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
        void resourceExhausted_throwsRateLimitException() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);

            String errorBody = "{\"error\":{\"code\":429,\"message\":\"RESOURCE_EXHAUSTED\"}}";
            ResponseEntity<String> response = new ResponseEntity<>(errorBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(response);

            assertThatThrownBy(() -> gateway.sendHttpRequest("prompt", "key"))
                    .isInstanceOf(LlmRateLimitException.class)
                    .hasMessageContaining("RESOURCE_EXHAUSTED");
        }

        @SuppressWarnings("unchecked")
        @Test
        void recordMode_persistsResponse() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(replayService.isRecordMode()).thenReturn(true);
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);

            String responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}";
            ResponseEntity<String> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(response);

            gateway.sendHttpRequest("prompt", "key");

            verify(replayService).record(eq("prompt"), eq(responseBody), eq("GEMINI"), isNull());
        }

        @SuppressWarnings("unchecked")
        @Test
        void socketTimeout_withNoRetries_throwsLlmTimeoutException() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);
            when(preferencesService.getInt(eq("llm.retry.max"), anyInt())).thenReturn(0);
            when(preferencesService.getInt(eq("llm.timeout.seconds"), anyInt())).thenReturn(60);

            ResourceAccessException timeoutEx =
                    new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(timeoutEx);

            assertThatThrownBy(() -> gateway.sendHttpRequest("prompt", "key"))
                    .isInstanceOf(LlmTimeoutException.class)
                    .hasMessageContaining("60s")
                    .hasMessageContaining("llm.timeout.seconds");
        }

        @SuppressWarnings("unchecked")
        @Test
        void socketTimeout_withRetries_retriesBeforeThrowingLlmTimeoutException() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);
            when(preferencesService.getInt(eq("llm.retry.max"), anyInt())).thenReturn(1);
            when(preferencesService.getInt(eq("llm.timeout.seconds"), anyInt())).thenReturn(60);

            ResourceAccessException timeoutEx =
                    new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(timeoutEx);

            assertThatThrownBy(() -> gateway.sendHttpRequest("prompt", "key"))
                    .isInstanceOf(LlmTimeoutException.class);

            // Should have been called 2 times: 1 initial + 1 retry
            verify(restTemplate, times(2))
                    .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void socketTimeout_succeeds_onRetry() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);
            when(preferencesService.getInt(eq("llm.retry.max"), anyInt())).thenReturn(1);
            when(preferencesService.getInt(eq("llm.timeout.seconds"), anyInt())).thenReturn(60);

            ResourceAccessException timeoutEx =
                    new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
            String responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]}}]}";
            ResponseEntity<String> successResponse = new ResponseEntity<>(responseBody, HttpStatus.OK);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(timeoutEx)
                    .thenReturn(successResponse);

            String result = gateway.sendHttpRequest("prompt", "key");

            assertThat(result).isEqualTo(responseBody);
            verify(restTemplate, times(2))
                    .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }

        @SuppressWarnings("unchecked")
        @Test
        void serverError_withRetries_retriesBeforeReturningNull() {
            when(replayService.isReplayMode()).thenReturn(false);
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);
            when(preferencesService.getInt(eq("llm.retry.max"), anyInt())).thenReturn(1);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(HttpServerErrorException.create(
                            HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                            HttpHeaders.EMPTY, "error".getBytes(), null));

            String result = gateway.sendHttpRequest("prompt", "key");

            assertThat(result).isNull();
            // Should have been called 2 times: 1 initial + 1 retry
            verify(restTemplate, times(2))
                    .exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));
        }
    }

    @Nested
    class Throttle {

        @Test
        void noPreferencesService_noOp() {
            GeminiGateway gw = new GeminiGateway(providerConfig, restTemplate, objectMapper,
                    responseParser, null, requestFactory, replayService);
            // Should not throw
            gw.throttle();
        }

        @Test
        void rpmZero_noThrottle() {
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(0);
            // Should complete instantly
            gateway.throttle();
        }

        @Test
        void withinRpmLimit_noSleep() {
            when(preferencesService.getInt(eq("llm.rpm"), anyInt())).thenReturn(10);
            // Should complete instantly — no calls yet
            gateway.throttle();
        }
    }
}
