package com.taxonomy.analysis.service;

import com.sun.net.httpserver.HttpServer;
import com.taxonomy.extension.api.llm.ProviderId;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class OpenAiResponsesQuotaGatewayTest {
    private static final String PROMPT = "System: preserve source\nCatalogue: CP-1 Äußere Kommunikation\nRequirement: 界 🧭";
    private static final String RESPONSE = "{\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}],\"usage\":{\"input_tokens\":42,\"output_tokens\":15,\"total_tokens\":57}}";
    private final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    private final BudgetAuthority authority = new BudgetAuthority();
    private final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<tools.jackson.databind.JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicInteger countStatus = new AtomicInteger(200), generationStatus = new AtomicInteger(200);
    private final AtomicInteger retries = new AtomicInteger();
    private final AtomicReference<String> countBody = new AtomicReference<>("{\"object\":\"response.input_tokens\",\"input_tokens\":42}");
    HttpServer server; OpenAiCompatibleGateway gateway;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath(); paths.add(path);
            requests.add(json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            assertThat(authority.order.getLast()).isEqualTo("START");
            authority.order.add("HTTP");
            boolean count = path.endsWith("input_tokens");
            byte[] response = (count ? countBody.get() : RESPONSE).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "1");
            int status = count ? countStatus.get() : generationStatus.get();
            if (status == 503) (count ? countStatus : generationStatus).set(200);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        }); server.start();
        var transport = new OpenAiResponsesHttpTransport() {
            @Override ResponseEntity<String> exchange(String url, HttpHeaders headers, String body, int limit, long timeout) {
                assertThat(url).startsWith("https://api.openai.com/v1/responses");
                return super.exchange("http://127.0.0.1:" + server.getAddress().getPort() + URI.create(url).getPath(), headers, body, limit, timeout);
            }
        };
        AnalysisRuntimeSettings settings = (key, fallback) -> key.startsWith("llm.rpm") ? 0 : key.equals("llm.retry.max") ? retries.get() : fallback;
        gateway = new OpenAiCompatibleGateway(LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL, LlmProviderConfig.OPENAI_MODEL,
                0, new RestTemplate(), json, new LlmResponseParser(json), settings, null, null, transport);
        gateway.configureProviderQuotas(authority);
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void countAndGenerationHaveSeparateConfirmedAdmissionsAndExactlyTheSameCompleteInput() {
        var response = new AtomicReference<String>();
        assertThatCode(() -> response.set(gateway.sendHttpRequest(PROMPT, "fixture-key")))
                .as("The explicit supported adapter must perform count and generation").doesNotThrowAnyException();
        assertThat(response.get()).isEqualTo(RESPONSE); assertThat(gateway.extractResponseText(response.get())).isEqualTo("answer");
        assertThat(paths).containsExactly("/v1/responses/input_tokens", "/v1/responses");
        assertThat(authority.order).containsExactly("DEBIT", "START", "HTTP", "DEBIT", "START", "HTTP");
        assertThat(authority.ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(authority.settled.get(0).totalTokens()).isNull(); assertThat(authority.settled.get(1).totalTokens()).isEqualTo(57);
        assertThat(requests.get(0).get("input")).isEqualTo(requests.get(1).get("input"));
        assertThat(requests.get(0).get("truncation").asText()).isEqualTo("disabled");
        assertThat(requests.get(1).get("max_output_tokens").intValue()).isEqualTo(256);
    }
    @Test void anOverBoundCountIsChargedButNeverStartsGeneration() {
        countBody.set("{\"object\":\"response.input_tokens\",\"input_tokens\":5001}");
        assertThatThrownBy(() -> gateway.sendHttpRequest(PROMPT, "fixture-key")).isInstanceOf(LlmProviderException.class);
        assertThat(paths).containsExactly("/v1/responses/input_tokens"); assertThat(authority.ids).hasSize(1);
        assertThat(authority.settled.getFirst().totalTokens()).isNull();
    }

    @Test void structuredCompletionCountsTheSameSchemaAndKeepsItsReservedOutputCeiling() {
        assertThat(gateway.sendHttpRequest(PROMPT, "fixture-key", OpenAiCompletionOptionsTest.options(1024)))
                .isEqualTo(RESPONSE);

        assertThat(authority.order).containsExactly("DEBIT", "START", "HTTP", "DEBIT", "START", "HTTP");
        assertThat(authority.ids).hasSize(2).doesNotHaveDuplicates();
        assertThat(requests.get(0).path("text")).isEqualTo(requests.get(1).path("text"));
        var format = requests.get(1).path("text").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("name").asText()).isEqualTo("relation_decisions");
        assertThat(format.path("strict").booleanValue()).isTrue();
        assertThat(requests.get(1).path("max_output_tokens").intValue()).isEqualTo(256);
        assertThat(requests.get(1).path("temperature").doubleValue()).isZero();
    }

    @Test void smallerRequestedCompletionDoesNotAlterTheQuotaAuthorityContract() {
        gateway.sendHttpRequest(PROMPT, "fixture-key", OpenAiCompletionOptionsTest.options(128));
        assertThat(authority.ids).hasSize(2);
        assertThat(requests.get(1).path("max_output_tokens").intValue()).isEqualTo(128);
    }
    @Test void exhaustedCount429StillPublishesSharedCooldownAndRetainsUnknownUsage() {
        countStatus.set(429);
        assertThatThrownBy(() -> gateway.sendHttpRequest(PROMPT, "fixture-key")).isInstanceOf(LlmRateLimitException.class);
        assertThat(paths).containsExactly("/v1/responses/input_tokens"); assertThat(authority.cooldown.get()).isPositive();
        assertThat(authority.settled.getFirst().totalTokens()).isNull();
    }
    @Test void cancellationAfterCountCompletionNeverStartsGeneration() {
        var canceled = new AtomicBoolean();
        var guard = new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(70, 90, 1_048_576, 1000, 10_000),
                () -> new AnalysisMemoryGuard.Sample(1_048_576, 64 * 1_048_576), System::currentTimeMillis);
        try (var control = AnalysisRunControl.worker("count-cancel", canceled::get, guard);
             var meter = LlmTransportMeter.open(event -> canceled.set(true))) {
            assertThatThrownBy(() -> gateway.sendHttpRequest(PROMPT, "fixture-key")).isInstanceOf(AnalysisStoppedException.class);
        }
        assertThat(paths).containsExactly("/v1/responses/input_tokens"); assertThat(authority.ids).hasSize(1);
    }
    @Test void countAndGenerationRetriesEachSpendAnotherConfirmedPhysicalAdmission() {
        retries.set(1); countStatus.set(503); generationStatus.set(503);
        assertThat(gateway.sendHttpRequest(PROMPT, "fixture-key")).isEqualTo(RESPONSE);
        assertThat(paths).containsExactly("/v1/responses/input_tokens", "/v1/responses/input_tokens", "/v1/responses", "/v1/responses");
        assertThat(authority.ids).hasSize(4).doesNotHaveDuplicates(); assertThat(authority.settled).hasSize(4);
        assertThat(authority.settled.subList(0, 2)).allSatisfy(usage -> assertThat(usage.totalTokens()).isNull());
    }

    @Test void anUnconfirmedDurableJournalStartNeverReachesTheCountEndpoint() {
        var journal = new LlmTransportMeter.Journal() {
            public void started(LlmTransportMeter.Attempt attempt) { throw new IllegalStateException("acknowledgement lost"); }
            public void completed(LlmTransportMeter.Attempt attempt, LlmTransportMeter.Observation result) {
                fail("An unconfirmed START must never be completed as a physical call");
            }
        };
        try (var scope = LlmTransportMeter.openJournal(journal)) {
            assertThatThrownBy(() -> gateway.sendHttpRequest(PROMPT, "fixture-key"))
                    .isInstanceOf(LlmTransportMeter.JournalStartException.class);
        }
        assertThat(paths).isEmpty(); assertThat(authority.ids).hasSize(1);
        assertThat(authority.order).containsExactly("DEBIT"); assertThat(authority.settled).isEmpty();
    }

    @Test void anUnconfirmedBeforeTransportDebitNeverReachesTheCountEndpoint() {
        authority.failStart.set(true);
        assertThatThrownBy(() -> gateway.sendHttpRequest(PROMPT, "fixture-key"))
                .isInstanceOf(ProviderRateQuotas.UnavailableException.class);
        assertThat(paths).isEmpty(); assertThat(authority.ids).hasSize(1);
        assertThat(authority.order).containsExactly("DEBIT"); assertThat(authority.settled).isEmpty();
    }

    @Test void forgedUsageOnCountRemainsUnknownAndCannotAuthorizeGeneration() {
        countBody.set("{\"object\":\"response.input_tokens\",\"input_tokens\":42,\"usage\":{\"total_tokens\":1}}");
        assertThatThrownBy(() -> gateway.sendHttpRequest(PROMPT, "fixture-key")).isInstanceOf(LlmProviderException.class);
        assertThat(paths).containsExactly("/v1/responses/input_tokens"); assertThat(authority.ids).hasSize(1);
        assertThat(authority.settled.getFirst().totalTokens()).isNull();
    }

    @Test void aRecordedResponsesAnswerUsesNeitherHttpNorAnyActiveQuotaAuthority() {
        var recordings = mock(LlmRecordReplayService.class);
        when(recordings.isReplayMode()).thenReturn(true); when(recordings.replay(PROMPT)).thenReturn(Optional.of(RESPONSE));
        var replay = new OpenAiCompatibleGateway(LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL,
                LlmProviderConfig.OPENAI_MODEL, 0, new RestTemplate(), json, new LlmResponseParser(json),
                (key, fallback) -> 0, null, recordings);
        var quotas = mock(ProviderRateQuotas.class); replay.configureProviderQuotas(quotas);
        var observations = new ArrayList<LlmTransportMeter.Observation>();
        try (var scope = LlmTransportMeter.open(observations::add)) {
            assertThat(replay.sendHttpRequest(PROMPT, "fixture-key")).isEqualTo(RESPONSE);
            assertThat(replay.extractResponseText(RESPONSE)).isEqualTo("answer");
        }
        verifyNoInteractions(quotas); assertThat(paths).isEmpty();
        assertThat(observations).singleElement().satisfies(result -> {
            assertThat(result.source()).isEqualTo(LlmTransportMeter.Source.RECORDING_REPLAY);
            assertThat(result.usage().totalTokens()).isNull();
        });
    }

    static class BudgetAuthority implements ProviderRateQuotas {
        final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>(), ids = new ArrayList<>();
        final List<LlmTransportMeter.Usage> settled = new ArrayList<>(); final AtomicLong cooldown = new AtomicLong();
        final AtomicBoolean failStart = new AtomicBoolean();
        final TokenBudget budget = new TokenBudget(OpenAiResponsesTokenContract.ADAPTER, 5000, 256);
        public TokenBudget requiredBudget(ProviderId provider) { return budget; }
        public Decision tryReserve(ProviderId provider, String id, TokenBudget supplied, Runnable checkpoint) {
            if (!budget.equals(supplied)) throw new UnavailableException("Fixture requires the actual supported token contract", null);
            checkpoint.run(); ids.add(id); order.add("DEBIT");
            return new Decision(new Reservation() {
                public String id() { return id; }
                public void beforeTransport() {
                    checkpoint.run();
                    if (failStart.get()) throw new UnavailableException("Started debit acknowledgement lost", null);
                    order.add("START");
                }
                public void settle(LlmTransportMeter.Usage usage) { settled.add(usage); }
            }, 0);
        }
        public void defer(ProviderId provider, long delay) { cooldown.accumulateAndGet(delay, Math::max); }
    }
}
