package com.taxonomy.analysis.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LlmTransportMeterBoundaryTest {
    private static final String URL = "https://meter.invalid/v1/chat/completions";
    private final ObjectMapper json = new ObjectMapper();

    @Test void transportFailureRemainsUnknownUsageAndKeepsTheExistingExceptionContract() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL)).andRespond(withException(new IOException("private response context")));
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var scope = LlmTransportMeter.open(events::add)) {
            assertThatThrownBy(() -> gateway(http).sendHttpRequest("private prompt", "test-key"))
                    .isInstanceOf(LlmProviderException.class);
        }
        server.verify();
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.outcome()).isEqualTo(LlmTransportMeter.Outcome.TRANSPORT_ERROR);
        assertThat(event.statusCode()).isNull();
        assertThat(event.usage().totalTokens()).isNull();
        assertThat(event.toString()).doesNotContain("private", "test-key", URL);
    }

    @Test void malformedOrAmbiguousMetadataDoesNotReplaceTheActualProviderBody() {
        for (String body : List.of("[]", "null", "{", "{\"usage\":42}",
                "{\"usage\":{\"prompt_tokens\":5,\"prompt_tokens\":7}}",
                "{\"usage\":{\"prompt_tokens_details\":[]}}")) {
            var event = response(body);
            assertThat(event.usage().invalid()).as(body).isTrue();
            assertThat(event.usage().inputTokens()).as(body).isNull();
        }
    }

    @Test void validZeroLongBoundaryAndPartialMetadataRemainDistinctFromUnknownValues() {
        var zero = response("{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":0},\"completion_tokens_details\":{\"reasoning_tokens\":0}}}");
        assertThat(zero.usage()).isEqualTo(new LlmTransportMeter.Usage(0L, 0L, 0L, 0L, 0L, false));
        var maximum = response("{\"usage\":{\"total_tokens\":9223372036854775807}}");
        assertThat(maximum.usage().totalTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(maximum.usage().inputTokens()).isNull();
        assertThat(maximum.usage().invalid()).isFalse();
        var partial = response("{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":\"wrong\","
                + "\"prompt_tokens_details\":null,\"completion_tokens_details\":{\"reasoning_tokens\":-1}}}");
        assertThat(partial.usage().inputTokens()).isEqualTo(12L);
        assertThat(partial.usage().outputTokens()).isNull();
        assertThat(partial.usage().reasoningTokens()).isNull();
        assertThat(partial.usage().invalid()).isTrue();
    }

    @Test void aFailingObserverIsActuallyCalledOnceAndCannotReplaceTheProviderResult() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var observed = new AtomicInteger();
        try (var scope = LlmTransportMeter.open(event -> {
            observed.incrementAndGet();
            throw new IllegalStateException("private observer details");
        })) {
            assertThat(gateway(http).sendHttpRequest("private prompt", "test-key")).isEqualTo("{}");
        }
        server.verify();
        assertThat(observed).hasValue(1);
    }

    @Test void emptyCaptureMasksAnUnrelatedObserverAndRestoresItAfterward() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var gateway = gateway(http);
        Supplier<String> isolated = LlmTransportMeter.capture(() -> gateway.sendHttpRequest("source", "test-key"));
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var scope = LlmTransportMeter.open(events::add)) {
            isolated.get();
            assertThat(events).isEmpty();
            gateway.sendHttpRequest("source", "test-key");
        }
        server.verify();
        assertThat(events).hasSize(1);
    }

    @Test void wrongThreadAndOutOfOrderClosureCannotDiscardTheOwnersScope() throws Exception {
        var outer = LlmTransportMeter.open(event -> {});
        try {
            try (var inner = LlmTransportMeter.open(event -> {})) {
                assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("nesting order");
                try (var executor = Executors.newSingleThreadExecutor()) {
                    Throwable result = executor.submit(() -> catchThrowable(inner::close)).get();
                    assertThat(result).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("another thread");
                }
            }
        } finally { outer.close(); }
        outer.close(); // Idempotent owner cleanup does not reinstall old state.
        assertThatThrownBy(() -> LlmTransportMeter.open(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LlmTransportMeter.capture(null)).isInstanceOf(NullPointerException.class);
    }

    private LlmTransportMeter.Observation response(String body) {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var scope = LlmTransportMeter.open(events::add)) {
            assertThat(gateway(http).sendHttpRequest("source", "test-key")).isEqualTo(body);
        }
        server.verify();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private OpenAiCompatibleGateway gateway(RestTemplate http) {
        return new OpenAiCompatibleGateway(LlmProvider.OPENAI, URL, "test-model", 0,
                http, json, new LlmResponseParser(json), null, null, null);
    }
}
