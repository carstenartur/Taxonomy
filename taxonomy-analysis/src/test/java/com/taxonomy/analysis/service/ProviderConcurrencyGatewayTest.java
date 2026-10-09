package com.taxonomy.analysis.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProviderConcurrencyGatewayTest {

    @ParameterizedTest
    @EnumSource(value = LlmProvider.class, names = {"GEMINI", "OPENAI"})
    void registryWrapsEveryPhysicalAttemptAndReleasesBeforeRetry(LlmProvider provider) {
        var events = new ArrayList<String>();
        var inFlight = new AtomicInteger();
        ProviderConcurrencyPermits permits = (actual, checkpoint) -> {
            assertThat(actual).isEqualTo(provider.id());
            checkpoint.run();
            events.add("acquire");
            assertThat(inFlight.incrementAndGet()).isOne();
            return () -> { events.add("release"); inFlight.decrementAndGet(); };
        };
        var http = mock(RestTemplate.class);
        var attempts = new AtomicInteger();
        when(http.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(call -> {
                    assertThat(inFlight.get()).isOne();
                    events.add("http");
                    if (attempts.getAndIncrement() == 0) {
                        var headers = new HttpHeaders();
                        headers.set("Retry-After", "0");
                        throw HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "limited",
                                headers, new byte[0], null);
                    }
                    return ResponseEntity.ok("{}");
                });
        assertThat(registry(http, null, permits).getGateway(provider).sendHttpRequest("fixture", "fixture-key"))
                .isEqualTo("{}");
        assertThat(events).containsExactly("acquire", "http", "release", "acquire", "http", "release");
        assertThat(inFlight.get()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = LlmProvider.class, names = {"GEMINI", "OPENAI"})
    void cancellationOrMissingCapacityNeverStartsHttp(LlmProvider provider) {
        var http = mock(RestTemplate.class);
        ProviderConcurrencyPermits cancelled = (actual, checkpoint) -> {
            throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
        };
        assertThatThrownBy(() -> registry(http, null, cancelled).getGateway(provider)
                .sendHttpRequest("fixture", "fixture-key")).isInstanceOf(AnalysisStoppedException.class);
        ProviderConcurrencyPermits unavailable = (actual, checkpoint) -> {
            throw new ProviderConcurrencyPermits.UnavailableException("No cluster capacity", null);
        };
        assertThatThrownBy(() -> registry(http, null, unavailable).getGateway(provider)
                .sendHttpRequest("fixture", "fixture-key"))
                .isInstanceOf(ProviderConcurrencyPermits.UnavailableException.class);
        verifyNoInteractions(http);
    }

    @ParameterizedTest
    @EnumSource(value = LlmProvider.class, names = {"GEMINI", "OPENAI"})
    void replayConsumesNoClusterPermit(LlmProvider provider) {
        var http = mock(RestTemplate.class);
        var replay = mock(LlmRecordReplayService.class);
        when(replay.isReplayMode()).thenReturn(true);
        when(replay.replay("fixture")).thenReturn(Optional.of("recorded"));
        ProviderConcurrencyPermits forbidden = (actual, checkpoint) -> {
            throw new AssertionError("Replay acquired network capacity");
        };
        assertThat(registry(http, replay, forbidden).getGateway(provider).sendHttpRequest("fixture", "fixture-key"))
                .isEqualTo("recorded");
        verifyNoInteractions(http);
    }

    private static LlmGatewayRegistry registry(RestTemplate http, LlmRecordReplayService replay,
                                               ProviderConcurrencyPermits permits) {
        var config = mock(LlmProviderConfig.class);
        when(config.getGeminiUrl()).thenReturn("http://fixture.invalid/?key=");
        var settings = mock(AnalysisRuntimeSettings.class);
        when(settings.getInt(eq("llm.retry.max"), anyInt())).thenReturn(1);
        var registry = new LlmGatewayRegistry(config, http, JsonMapper.builder().build(), settings, null, replay);
        registry.configureProviderPermits(permits);
        return registry;
    }
}
