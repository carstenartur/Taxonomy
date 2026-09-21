package com.taxonomy.analysis.service;

import com.taxonomy.analysis.reformulation.NodeReformulationService;
import com.taxonomy.reformulation.NodeSynthesisInput;
import com.taxonomy.reformulation.ReformulationBaseline;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LlmTransportMeterTest {
    private static final String URL = "https://meter.invalid/v1/chat/completions";
    private static final String PROMPT = "PRIVATE_REQUIREMENT";
    private static final String KEY = "fixture-secret";
    private static final String USAGE = "\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":15,\"total_tokens\":57,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":20},\"completion_tokens_details\":{\"reasoning_tokens\":5}}";
    private final ObjectMapper json = new ObjectMapper();

    @Test void recordsEveryHttpRetryRatherThanOneLogicalGatewayCall() {
        var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess("{" + USAGE + "}", MediaType.APPLICATION_JSON));
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var ignored = LlmTransportMeter.open(events::add)) { gateway(http, null).sendHttpRequest(PROMPT, KEY); }
        server.verify();
        assertThat(events).as("Both actual HTTP attempts, including the failed attempt").hasSize(2);
        assertThat(events.get(0).outcome()).isEqualTo(LlmTransportMeter.Outcome.HTTP_ERROR);
        assertThat(events.get(0).statusCode()).isEqualTo(503);
        assertThat(events.get(0).retryIndex()).isZero();
        assertThat(events.get(1).retryIndex()).isEqualTo(1);
        assertThat(events.get(0).invocationId()).isEqualTo(events.get(1).invocationId());
        var usage = events.get(1).usage();
        assertThat(usage.inputTokens()).isEqualTo(42L); assertThat(usage.outputTokens()).isEqualTo(15L);
        assertThat(usage.totalTokens()).isEqualTo(57L); assertThat(usage.cachedInputTokens()).isEqualTo(20L);
        assertThat(usage.reasoningTokens()).isEqualTo(5L); assertThat(usage.invalid()).isFalse();
        assertThat(events).allSatisfy(e -> { assertThat(e.durationMillis()).isNotNegative();
            assertThat(e.toString()).doesNotContain(PROMPT, KEY, URL); });
    }

    @Test void recordsGeminiRateLimitWithoutTurningItIntoSuccess() {
        var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL + "?key=" + KEY)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        var config = mock(LlmProviderConfig.class); when(config.getGeminiUrl()).thenReturn(URL + "?key=");
        var gateway = new GeminiGateway(config, http, json, new LlmResponseParser(json), null, null, null);
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var ignored = LlmTransportMeter.open(events::add)) {
            assertThatThrownBy(() -> gateway.sendHttpRequest(PROMPT, KEY)).isInstanceOf(LlmRateLimitException.class);
        }
        server.verify(); assertThat(events).hasSize(1);
        assertThat(events.getFirst().statusCode()).isEqualTo(429);
        assertThat(events.getFirst().usage().inputTokens()).isNull();
    }

    @Test void preservesReportedGeminiTotalsWithoutInferringBillingOrAddingReasoningTwice() {
        var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
        String body = "{\"usageMetadata\":{\"promptTokenCount\":20,\"candidatesTokenCount\":60,"
                + "\"totalTokenCount\":100,\"cachedContentTokenCount\":10,\"thoughtsTokenCount\":20}}";
        server.expect(requestTo(URL + "?key=" + KEY)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var config = mock(LlmProviderConfig.class); when(config.getGeminiUrl()).thenReturn(URL + "?key=");
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var ignored = LlmTransportMeter.open(events::add)) {
            new GeminiGateway(config, http, json, new LlmResponseParser(json), null, null, null).sendHttpRequest(PROMPT, KEY);
        }
        server.verify(); assertThat(events).hasSize(1); var usage = events.getFirst().usage();
        assertThat(usage.inputTokens()).isEqualTo(20L); assertThat(usage.outputTokens()).isEqualTo(60L);
        assertThat(usage.totalTokens()).isEqualTo(100L); assertThat(usage.cachedInputTokens()).isEqualTo(10L);
        assertThat(usage.reasoningTokens()).isEqualTo(20L);
    }

    @Test void replayNeverCountsHistoricTokensAsNewHttpUsage() {
        var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
        var replay = mock(LlmRecordReplayService.class);
        when(replay.isReplayMode()).thenReturn(true); when(replay.replay(PROMPT)).thenReturn(Optional.of("{" + USAGE + "}"));
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var ignored = LlmTransportMeter.open(events::add)) { gateway(http, replay).sendHttpRequest(PROMPT, KEY); }
        server.verify(); assertThat(events).hasSize(1);
        assertThat(events.getFirst().source()).isEqualTo(LlmTransportMeter.Source.RECORDING_REPLAY);
        assertThat(events.getFirst().statusCode()).isNull();
        assertThat(events.getFirst().usage().totalTokens()).isNull();
    }

    @Test void missingAndInvalidUsageAreNotReportedAsZero() {
        for (String body : List.of("{}", "{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":\"15\",\"total_tokens\":1.5}}",
                "{\"usage\":{\"prompt_tokens\":9223372036854775808}}")) {
            var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
            server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            var events = new ArrayList<LlmTransportMeter.Observation>();
            try (var ignored = LlmTransportMeter.open(events::add)) { gateway(http, null).sendHttpRequest(PROMPT, KEY); }
            server.verify(); assertThat(events).hasSize(1); var usage = events.getFirst().usage();
            assertThat(usage.inputTokens()).isNull(); assertThat(usage.outputTokens()).isNull(); assertThat(usage.totalTokens()).isNull();
            assertThat(usage.invalid()).isEqualTo(!body.equals("{}"));
        }
    }

    @Test void parserRepairIsASecondInvocationAndStillRecordsUsageFromRejectedWording() {
        var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
        String valid = "{\"summary\":\"Proposed wording\",\"statementProposals\":[],\"preservedStatementIds\":[],"
                + "\"questionProposals\":[],\"preservedQuestionIds\":[],\"uncoveredSourceRefs\":[],\"conflictCandidates\":[]}";
        server.expect(requestTo(URL)).andRespond(withSuccess(envelope("{"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withSuccess(envelope(valid), MediaType.APPLICATION_JSON));
        var config = mock(LlmProviderConfig.class); when(config.getActiveProvider()).thenReturn(LlmProvider.CUSTOM_OPENAI);
        when(config.isProviderConfigured(LlmProvider.CUSTOM_OPENAI)).thenReturn(true); when(config.getApiKey(LlmProvider.CUSTOM_OPENAI)).thenReturn(KEY);
        var transport = gateway(http, null);
        var registry = mock(LlmGatewayRegistry.class); when(registry.getGateway(LlmProvider.CUSTOM_OPENAI)).thenReturn(transport);
        var scope = new ReformulationBaseline.Scope("repository", "workspace", "draft", 1L, 1L);
        var baseline = ReformulationBaseline.freeze(new ReformulationBaseline.Source(scope, 1L, PROMPT),
                new ReformulationBaseline.Snapshot(scope, "snapshot", 1L, "{}"), Map.of(), "en", "test");
        var input = new NodeSynthesisInput(baseline, "@document", null, "Source-only input", List.of(), List.of(),
                List.of(), Map.of(), List.of(), List.of(), "Preserve original");
        var events = new ArrayList<LlmTransportMeter.Observation>();
        try (var ignored = LlmTransportMeter.open(events::add)) {
            assertThat(new NodeReformulationService(registry, config, json).synthesize(input).summary()).isEqualTo("Proposed wording");
        }
        server.verify(); assertThat(events).hasSize(2);
        assertThat(events.get(0).invocationId()).isNotEqualTo(events.get(1).invocationId());
        assertThat(events).allSatisfy(e -> { assertThat(e.retryIndex()).isZero(); assertThat(e.usage().inputTokens()).isEqualTo(42L); });
    }

    @Test void nestedAndCapturedScopesNeverLeakIntoUnrelatedWork() throws Exception {
        var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
        for (int i = 0; i < 5; i++) server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        var gateway = gateway(http, null); var outer = new CopyOnWriteArrayList<LlmTransportMeter.Observation>();
        var inner = new ArrayList<LlmTransportMeter.Observation>();
        try (var worker = Executors.newSingleThreadExecutor()) {
            java.util.function.Supplier<String> captured;
            try (var ignored = LlmTransportMeter.open(outer::add)) {
                gateway.sendHttpRequest(PROMPT, KEY);
                try (var nested = LlmTransportMeter.open(inner::add)) { gateway.sendHttpRequest(PROMPT, KEY); }
                captured = LlmTransportMeter.capture(() -> gateway.sendHttpRequest(PROMPT, KEY));
                gateway.sendHttpRequest(PROMPT, KEY);
            }
            worker.submit(captured::get).get();
            worker.submit(() -> gateway.sendHttpRequest(PROMPT, KEY)).get();
        }
        server.verify(); assertThat(outer).hasSize(3); assertThat(inner).hasSize(1);
    }

    @Test void observerFailureDoesNotCauseASecondProviderRequest() {
        var http = new RestTemplate(); var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        try (var ignored = LlmTransportMeter.open(e -> { throw new IllegalStateException("Observer failure"); })) {
            assertThat(gateway(http, null).sendHttpRequest(PROMPT, KEY)).isEqualTo("{}");
        }
        server.verify();
    }

    private OpenAiCompatibleGateway gateway(RestTemplate http, LlmRecordReplayService replay) {
        var settings = mock(AnalysisRuntimeSettings.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0).equals("llm.retry.max") ? 1 : call.getArgument(1));
        return new OpenAiCompatibleGateway(LlmProvider.CUSTOM_OPENAI, URL, "fixture-model", 0, http, json,
                new LlmResponseParser(json), settings, null, replay);
    }
    private String envelope(String content) {
        return "{\"choices\":[{\"message\":{\"content\":" + json.writeValueAsString(content) + "}}]," + USAGE + "}";
    }
}
