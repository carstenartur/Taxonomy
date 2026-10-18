package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.ProviderId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** Shared bodies for the individually named JUnit cases; no external provider. */
final class ProviderPermitLifecycleCases {
    record Check(String name, Runnable action) { }

    static List<Check> checks() {
        return List.of(
            new Check("durable capacity is confirmed before the request supplier", ProviderPermitLifecycleCases::admissionPrecedesHttp),
            new Check("unconfirmed capacity permits zero HTTP", ProviderPermitLifecycleCases::failedAdmissionHasNoHttp),
            new Check("complete response reaches the capacity owner without telemetry", ProviderPermitLifecycleCases::responseWithoutTelemetry),
            new Check("HTTP rejection reaches the capacity owner", ProviderPermitLifecycleCases::httpFailure),
            new Check("transport failure does not manufacture a response", ProviderPermitLifecycleCases::transportFailure),
            new Check("journal failure still leaves uncertain capacity closed", ProviderPermitLifecycleCases::journalFailure),
            new Check("nested observers preserve capacity ownership", ProviderPermitLifecycleCases::nestedObserver),
            new Check("captured tasks do not inherit capacity ownership", ProviderPermitLifecycleCases::capturedObserver),
            new Check("each physical retry has a separate capacity lifecycle", ProviderPermitLifecycleCases::distinctAttempts),
            new Check("ordinary local execution remains unchanged", ProviderPermitLifecycleCases::localExecution));
    }

    private static final class Capacity implements ProviderConcurrencyPermits {
        final List<String> events = new ArrayList<>();
        boolean reject;
        public Permit acquire(ProviderId provider, Runnable checkpoint) {
            checkpoint.run(); events.add("acquire");
            return new Permit() {
                // These methods intentionally compile against the pre-fix functional interface too.
                public void beforeTransport() {
                    events.add("durable-start");
                    if (reject) throw new UnavailableException("unconfirmed", null);
                }
                public void responseReceived(int status) { events.add("response:" + status); }
                public void close() { events.add("close"); }
            };
        }
    }
    private static LlmRequestAdmission admission(Capacity capacity) {
        var admission = new LlmRequestAdmission(LlmProvider.OPENAI, 60, null);
        admission.configure(capacity);
        return admission;
    }
    private static ResponseEntity<String> exchange(Capacity capacity, Supplier<ResponseEntity<String>> http) {
        return admission(capacity).execute(() -> LlmTransportMeter.exchange(
                null, "OPENAI", 0, JsonMapper.builder().build(), http));
    }
    private static void admissionPrecedesHttp() {
        var capacity = new Capacity();
        admission(capacity).execute(() -> {
            capacity.events.add("http"); return "ok";
        });
        equal(capacity.events, List.of("acquire", "durable-start", "http", "close"));
    }
    private static void failedAdmissionHasNoHttp() {
        var capacity = new Capacity(); capacity.reject = true;
        var calls = new AtomicInteger();
        expect(ProviderConcurrencyPermits.UnavailableException.class,
                () -> admission(capacity).execute(calls::incrementAndGet));
        equal(calls.get(), 0);
        equal(capacity.events, List.of("acquire", "durable-start", "close"));
    }
    private static void responseWithoutTelemetry() {
        var capacity = new Capacity();
        equal(exchange(capacity, () -> ResponseEntity.ok("{}" )).getStatusCode().value(), 200);
        equal(capacity.events, List.of("acquire", "durable-start", "response:200", "close"));
    }
    private static void httpFailure() {
        var capacity = new Capacity();
        var error = new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS);
        equal(expect(HttpClientErrorException.class, () -> exchange(capacity, () -> { throw error; })), error);
        equal(capacity.events, List.of("acquire", "durable-start", "response:429", "close"));
    }
    private static void transportFailure() {
        var capacity = new Capacity();
        var error = new IllegalStateException("socket disconnected");
        equal(expect(IllegalStateException.class, () -> exchange(capacity, () -> { throw error; })), error);
        equal(capacity.events, List.of("acquire", "durable-start", "close"));
    }
    private static void journalFailure() {
        var capacity = new Capacity(); var calls = new AtomicInteger();
        try (var ignored = LlmTransportMeter.openJournal(new LlmTransportMeter.Journal() {
            public void started(LlmTransportMeter.Attempt a) { throw new IllegalStateException("journal unavailable"); }
            public void completed(LlmTransportMeter.Attempt a, LlmTransportMeter.Observation o) {
                throw new AssertionError("No completion without start");
            }
        })) {
            expect(LlmTransportMeter.JournalStartException.class, () -> exchange(capacity, () -> {
                calls.incrementAndGet(); return ResponseEntity.ok("{}");
            }));
        }
        equal(calls.get(), 0);
        equal(capacity.events, List.of("acquire", "durable-start", "close"));
    }
    private static void nestedObserver() {
        var capacity = new Capacity();
        admission(capacity).execute(() -> {
            try (var ignored = LlmTransportMeter.open(o -> {})) {
                return LlmTransportMeter.exchange(null, "OPENAI", 0, JsonMapper.builder().build(), () -> ResponseEntity.ok("{}"));
            }
        });
        equal(capacity.events, List.of("acquire", "durable-start", "response:200", "close"));
    }
    private static void capturedObserver() {
        var capacity = new Capacity();
        Supplier<ResponseEntity<String>> captured = admission(capacity).execute(() ->
                LlmTransportMeter.capture(() -> LlmTransportMeter.exchange(null, "OPENAI", 0,
                        JsonMapper.builder().build(), () -> ResponseEntity.ok("{}"))));
        equal(capacity.events, List.of("acquire", "durable-start", "close"));
        captured.get();
        equal(capacity.events, List.of("acquire", "durable-start", "close"));
    }
    private static void distinctAttempts() {
        var capacity = new Capacity(); var admission = admission(capacity);
        for (int i=0;i<2;i++) admission.execute(() -> LlmTransportMeter.exchange(null, "OPENAI", 0,
                JsonMapper.builder().build(), () -> ResponseEntity.ok("{}")));
        equal(capacity.events, List.of("acquire", "durable-start", "response:200", "close",
                "acquire", "durable-start", "response:200", "close"));
    }
    private static void localExecution() {
        equal(new LlmRequestAdmission(LlmProvider.OPENAI, 60, null).execute(() -> "local"), "local");
    }
    static void equal(Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    static <T extends Throwable> T expect(Class<T> type, Runnable action) {
        try { action.run(); } catch (Throwable e) {
            if (type.isInstance(e)) return type.cast(e);
            throw new AssertionError("Expected " + type.getName() + ", got " + e, e);
        }
        throw new AssertionError("Expected " + type.getName());
    }
}
