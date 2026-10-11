package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class OpenAiResponsesUsageTest {
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    @Test void actualResponsesUsagePreservesAllGeneratedAndCachedTokensWithoutAnEstimate() {
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var meter = LlmTransportMeter.open(events::add)) {
            LlmTransportMeter.exchange(UUID.randomUUID().toString(), "OPENAI", 0, json, () -> ResponseEntity.ok(
                    "{\"object\":\"response\",\"usage\":{\"input_tokens\":42,\"output_tokens\":15,\"total_tokens\":57,"
                    + "\"input_tokens_details\":{\"cached_tokens\":20},\"output_tokens_details\":{\"reasoning_tokens\":5}}}"));
        }
        assertThat(events).hasSize(1); var usage = events.getFirst().usage();
        assertThat(usage.inputTokens()).isEqualTo(42); assertThat(usage.outputTokens()).isEqualTo(15);
        assertThat(usage.totalTokens()).isEqualTo(57); assertThat(usage.cachedInputTokens()).isEqualTo(20);
        assertThat(usage.reasoningTokens()).isEqualTo(5); assertThat(usage.invalid()).isFalse();
    }
    @Test void aCountOperationCanNeverAuthorizeKnownUsageEvenWithAForgedUsageField() {
        var events = new ArrayList<LlmTransportMeter.Observation>(); var settled = new ArrayList<LlmTransportMeter.Usage>();
        var reservation = new ProviderRateQuotas.Reservation() {
            public String id() { return UUID.randomUUID().toString(); }
            public void beforeTransport() { }
            public void settle(LlmTransportMeter.Usage usage) { settled.add(usage); }
        };
        try (var quota = LlmTransportMeter.openQuota(reservation); var meter = LlmTransportMeter.open(events::add)) {
            LlmTransportMeter.exchangeCount(UUID.randomUUID().toString(), "OPENAI", 0, json, () -> ResponseEntity.ok(
                    "{\"object\":\"response.input_tokens\",\"input_tokens\":42,\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"));
        }
        assertThat(events).hasSize(1); assertThat(settled).hasSize(1);
        assertThat(events.getFirst().usage().totalTokens()).as("Count is never inference usage").isNull();
        assertThat(settled.getFirst().inputTokens()).isNull(); assertThat(settled.getFirst().outputTokens()).isNull();
        assertThat(settled.getFirst().totalTokens()).isNull();
    }
}
