package com.taxonomy.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.SimpleRequestExpectationManager;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ScenarioRequestExpectationManagerTest {
    private static final String URL = ScenarioLlmConfiguration.URL;
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path directory;

    @Test void completedLargeExchangesRetainOnlyTransportMetadataAndEveryFullJournalByte() throws Exception {
        var playback = ScenarioLlmPlayback.flood();
        Path journal = directory.resolve("complete-calls.jsonl");
        playback.recordCallsTo(journal);
        // This characterizes transport storage, not the scenario's independently enforced
        // model-input budget. The normal fixture identity and complete source stay intact.
        String prompt = ScenarioLlmPlaybackTest.prompt(playback, playback.fixture().path("replies").get(0))
                + "\nTransport characterization: " + "complete-α-\ud83d\ude00-".repeat(20_000) + "tail";
        String payload = envelope(prompt);
        var manager = new ObservedManager();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build(manager);
        var client = builder.build();
        var transportHeaders = new ArrayList<HttpHeaders>();
        server.expect(times(3), requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(payload)).andRespond(request -> {
                    var headers = new HttpHeaders();
                    request.getHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
                    transportHeaders.add(headers);
                    String requestPrompt = json.readTree(((MockClientHttpRequest) request).getBodyAsString())
                            .at("/messages/0/content").asText();
                    return withSuccess(playback.respond(requestPrompt), MediaType.APPLICATION_JSON).createResponse(request);
                });
        var responses = new ArrayList<String>();
        for (int index = 0; index < 3; index++) responses.add(client.post().uri(URL)
                .contentType(MediaType.APPLICATION_JSON).header("X-Scenario-Exchange", Integer.toString(index))
                .body(payload).retrieve().body(String.class));
        server.verify();
        var recovered = ScenarioCallJournal.read(journal);
        assertThat(recovered).containsExactlyElementsOf(playback.calls()).hasSize(3);
        for (int index = 0; index < recovered.size(); index++) {
            assertThat(recovered.get(index).prompt()).isEqualTo(prompt);
            assertThat(recovered.get(index).response()).isEqualTo(responses.get(index));
            assertThat(recovered.get(index).promptSha256()).isEqualTo(ScenarioLlmPlayback.sha256(prompt));
            assertThat(recovered.get(index).responseSha256()).isEqualTo(ScenarioLlmPlayback.sha256(responses.get(index)));
        }
        var requests = manager.requests();
        assertThat(requests).hasSize(3);
        for (int index = 0; index < requests.size(); index++) {
            assertThat(requests.get(index).getURI().toString()).isEqualTo(URL);
            assertThat(requests.get(index).getMethod()).isEqualTo(HttpMethod.POST);
            assertThat(requests.get(index).getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(requests.get(index).getHeaders().getFirst("X-Scenario-Exchange")).isEqualTo(Integer.toString(index));
            assertThat(requests.get(index).getHeaders()).isEqualTo(transportHeaders.get(index));
        }
        // The one deliberately narrow framework-history characterization: successful
        // transport bodies must not remain rooted in Spring's request-history list.
        assertThat(requests.stream().mapToLong(request -> ((MockClientHttpRequest) request).getBodyAsBytes().length).sum())
                .as("Full accepted texts are durable in the journal, not retained again by mock transport history")
                .isZero();
    }

    @Test void expectationCountsOrderAndVerificationDiagnosticsMatchTheDefaultManager() throws Exception {
        for (var action : List.of("missing", "extra", "wrong-uri", "wrong-method", "wrong-body", "out-of-order")) {
            assertThat(failureScenario(new ObservedManager(), action)).as(action)
                    .isEqualTo(failureScenario(new SimpleRequestExpectationManager(), action));
        }
    }

    private List<String> failureScenario(SimpleRequestExpectationManager manager, String action) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build(manager);
        var client = builder.build();
        var responses = new AtomicInteger();
        server.expect(action.equals("missing") ? times(2) : once(), requestTo(URL + "/one"))
                .andExpect(method(HttpMethod.POST)).andExpect(content().string("first-complete-body"))
                .andRespond(request -> { responses.incrementAndGet(); return withSuccess("first", MediaType.TEXT_PLAIN).createResponse(request); });
        if (action.equals("out-of-order")) server.expect(once(), requestTo(URL + "/two"))
                .andRespond(withSuccess("second", MediaType.TEXT_PLAIN));
        var outcomes = new ArrayList<String>();
        String uri = URL + (action.equals("wrong-uri") || action.equals("out-of-order") ? "/two" : "/one");
        HttpMethod method = action.equals("wrong-method") ? HttpMethod.PUT : HttpMethod.POST;
        String body = action.equals("wrong-body") ? "different-complete-body" : "first-complete-body";
        outcomes.add(outcome(() -> client.method(method).uri(uri).body(body).retrieve().body(String.class)));
        if (action.equals("extra")) outcomes.add(outcome(() -> client.post().uri(URL + "/one")
                .body("first-complete-body").retrieve().body(String.class)));
        outcomes.add(outcome(() -> { server.verify(); return "verified"; }));
        outcomes.add("responses=" + responses.get());
        return outcomes;
    }

    @Test void failedMatchersKeepTheirOriginalCompleteRequestAndFailureState() throws Exception {
        var manager = new ObservedManager();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build(manager);
        var client = builder.build();
        server.expect(once(), requestTo(URL)).andExpect(content().string("expected"))
                .andRespond(withSuccess("not returned", MediaType.TEXT_PLAIN));
        String actual = "complete failed request " + "x".repeat(16_384);
        assertThatThrownBy(() -> client.post().uri(URL).body(actual).retrieve().body(String.class))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(server::verify).isInstanceOf(AssertionError.class);
        assertThat(manager.requests()).hasSize(1);
        assertThat(((MockClientHttpRequest) manager.requests().getFirst()).getBodyAsString()).isEqualTo(actual);
    }

    @Test void failedResponseStillConsumesTheSameCountAndRetainsItsOriginalRequest() throws Exception {
        var manager = new ObservedManager();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build(manager);
        var client = builder.build();
        server.expect(once(), requestTo(URL)).andRespond(request -> { throw new IOException("deliberate response failure"); });
        String payload = "complete response-failure input " + "y".repeat(16_384);
        assertThatThrownBy(() -> client.post().uri(URL).body(payload).retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class).hasRootCauseMessage("deliberate response failure");
        server.verify(); // Spring consumes the matched count before invoking its response creator.
        assertThat(((MockClientHttpRequest) manager.requests().getFirst()).getBodyAsString()).isEqualTo(payload);
    }

    @Test void heldResponseDoesNotBlockAnotherRequestOrChangeHistoryArrivalOrder() throws Exception {
        var manager = new ObservedManager();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build(manager);
        var client = builder.build();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        String held = "held-complete-request " + "h".repeat(16_384);
        server.expect(once(), requestTo(URL)).andExpect(content().string(held)).andRespond(request -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Response release was not delivered");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            return withSuccess("held response", MediaType.TEXT_PLAIN).createResponse(request);
        });
        server.expect(once(), requestTo(URL)).andExpect(content().string("quick-complete-request"))
                .andRespond(withSuccess("quick response", MediaType.TEXT_PLAIN));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> client.post().uri(URL).header("X-Scenario-Order", "held")
                    .body(held).retrieve().body(String.class));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> client.post().uri(URL).header("X-Scenario-Order", "quick").body("quick-complete-request")
                        .retrieve().body(String.class));
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("quick response");
                assertThat(first.isDone()).isFalse();
                server.verify();
                assertThat(((MockClientHttpRequest) manager.requests().getFirst()).getBodyAsString()).isEqualTo(held);
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("held response");
        } finally { release.countDown(); }
        assertThat(manager.requests()).extracting(request -> request.getHeaders().getFirst("X-Scenario-Order"))
                .containsExactly("held", "quick");
        assertThat(manager.requests()).allSatisfy(request -> assertThat(((MockClientHttpRequest) request).getBodyAsBytes().length).isZero());
    }

    @Test void actualScenarioWireValidationAndDurablePlaybackRemainUnchanged() throws Exception {
        var template = new RestTemplate();
        var playback = new ScenarioLlmConfiguration().scenarioLlmPlayback(template, new MockEnvironment());
        Path journal = directory.resolve("actual-wire.jsonl"); playback.recordCallsTo(journal);
        String prompt = ScenarioLlmPlaybackTest.prompt(playback, playback.fixture().path("replies").get(0));
        String response = template.postForObject(URL, entity(envelope(prompt)), String.class);
        assertThat(response).isEqualTo(playback.calls().getFirst().response());
        assertThat(ScenarioCallJournal.read(journal)).containsExactlyElementsOf(playback.calls());
        for (var kind : List.of("url", "method", "envelope")) {
            var rejectedTemplate = new RestTemplate();
            var rejected = new ScenarioLlmConfiguration().scenarioLlmPlayback(rejectedTemplate, new MockEnvironment());
            rejected.recordCallsTo(directory.resolve(kind + "-rejected.jsonl"));
            assertThatThrownBy(() -> {
                if (kind.equals("method")) rejectedTemplate.getForObject(URL, String.class);
                else rejectedTemplate.postForObject(kind.equals("url") ? URL + "/unrecognized" : URL,
                        entity(kind.equals("envelope") ? "{\"model\":\"foreign\",\"messages\":[]}" : envelope(prompt)), String.class);
            }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unexpected");
            assertThat(rejected.calls()).isEmpty(); assertThat(rejected.failures()).hasSize(1);
            assertThat(ScenarioCallJournal.read(directory.resolve(kind + "-rejected.jsonl"))).isEmpty();
        }
    }

    private HttpEntity<String> entity(String body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private String envelope(String prompt) {
        return json.writeValueAsString(Map.of("model", "scenario-fixture", "messages",
                List.of(Map.of("role", "user", "content", prompt))));
    }

    private static String outcome(CheckedCall action) throws Exception {
        try { return "returned:" + action.run(); }
        catch (AssertionError failure) { return failure.getClass().getName() + ":" + failure.getMessage(); }
    }
    @FunctionalInterface private interface CheckedCall { String run() throws Exception; }

    private static final class ObservedManager extends ScenarioRequestExpectationManager {
        List<ClientHttpRequest> requests() {
            var requests = getRequests();
            synchronized (requests) { return List.copyOf(requests); }
        }
    }
}
