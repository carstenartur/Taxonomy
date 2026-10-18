package com.taxonomy.analysis.service;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Optional, explicitly scoped observation of actual transport attempts. Does not retain
 * prompts, response bodies, URLs or credentials and does not infer provider billing.
 * Consumers are best-effort; an optional journal must durably admit each attempt before transport.
 */
public final class LlmTransportMeter {
    public enum Source { HTTP, RECORDING_REPLAY }
    public enum Outcome { RESPONSE, HTTP_ERROR, TRANSPORT_ERROR }
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens,
                        Long cachedInputTokens, Long reasoningTokens, boolean invalid) {}
    public record Observation(String invocationId, String provider, Source source, int retryIndex,
                              Integer statusCode, Outcome outcome, long durationMillis, Usage usage) {}

    public record Attempt(String id, String invocationId, String provider, Source source, int retryIndex) {}
    /** Implementations commit started() before returning. Completion failure leaves an unresolved start. */
    public interface Journal {
        void started(Attempt attempt);
        void completed(Attempt attempt, Observation result);
    }
    public static final class JournalStartException extends RuntimeException {
        private JournalStartException() { super("USAGE_RECORDING_UNAVAILABLE"); }
    }

    private static final Usage UNKNOWN = new Usage(null, null, null, null, null, false);
    private static final Usage INVALID = new Usage(null, null, null, null, null, true);
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private LlmTransportMeter() {}

    public static Scope open(Consumer<Observation> observer) {
        return new Scope(Objects.requireNonNull(observer, "observer"), journal(), quota(), capacity());
    }

    public static Scope openJournal(Journal journal) {
        return new Scope(observer(), Objects.requireNonNull(journal, "journal"), quota(), capacity());
    }

    /** Binds exactly the already committed debit to its physical transport attempt. */
    static Scope openQuota(ProviderRateQuotas.Reservation reservation) {
        return new Scope(observer(), journal(), Objects.requireNonNull(reservation, "reservation"), capacity());
    }

    /** Capacity ownership is per physical admission, never inherited by captured work. */
    static Scope openCapacity(ProviderConcurrencyPermits.Permit capacity) {
        return new Scope(observer(), journal(), quota(), Objects.requireNonNull(capacity, "capacity"));
    }

    /** Explicit capture only: pooled threads do not inherit another request's observer. */
    public static <T> Supplier<T> capture(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        Consumer<Observation> observer = observer();
        Journal journal = journal();
        return () -> {
            try (var ignored = new Scope(observer, journal, null, null)) { return operation.get(); }
        };
    }

    static String newInvocation() {
        return observer() == null && journal() == null ? null : UUID.randomUUID().toString();
    }

    static void replay(String invocationId, String provider) {
        Consumer<Observation> observer = observer();
        Journal journal = journal();
        if ((observer == null && journal == null) || invocationId == null) return;
        Attempt attempt = begin(journal, invocationId, provider, Source.RECORDING_REPLAY, 0, null);
        completed(journal, attempt, observer, null, new Observation(invocationId, provider, Source.RECORDING_REPLAY,
                0, null, Outcome.RESPONSE, 0, UNKNOWN));
    }

    /** Journal admission is OUTSIDE transport error handling and therefore never causes a retry. */
    static ResponseEntity<String> exchangeCount(String invocationId, String provider, int retryIndex,
            ObjectMapper json, Supplier<ResponseEntity<String>> operation) {
        return exchange(invocationId, provider, retryIndex, json, operation, false);
    }

    static ResponseEntity<String> exchange(String invocationId, String provider, int retryIndex,
            ObjectMapper json, Supplier<ResponseEntity<String>> operation) {
        return exchange(invocationId, provider, retryIndex, json, operation, true);
    }

    private static ResponseEntity<String> exchange(String invocationId, String provider, int retryIndex,
            ObjectMapper json, Supplier<ResponseEntity<String>> operation, boolean inferenceUsage) {
        Consumer<Observation> observer = observer();
        Journal journal = journal();
        var reservation = quota();
        var capacity = capacity();
        if (observer == null && journal == null && reservation == null && capacity == null) return operation.get();
        if (invocationId == null) invocationId = UUID.randomUUID().toString();
        Attempt attempt = begin(journal, invocationId, provider, Source.HTTP, retryIndex, reservation);
        // A worker/DB pause after durable START cannot revive an expired or already consumed debit.
        // Confirmation failure remains outside retry/error handling: no HTTP and no quota compensation.
        if (reservation != null) reservation.beforeTransport();
        long started = System.nanoTime();
        ResponseEntity<String> response;
        try {
            response = operation.get();
        } catch (RuntimeException failure) {
            Integer status = null;
            Usage usage = UNKNOWN;
            Outcome outcome = Outcome.TRANSPORT_ERROR;
            if (failure instanceof RestClientResponseException http) {
                status = http.getStatusCode().value();
                outcome = Outcome.HTTP_ERROR;
                if (capacity != null) capacity.responseReceived(status);
                usage = inferenceUsage ? usage(json, provider, http.getResponseBodyAsString()) : UNKNOWN;
            }
            completed(journal, attempt, observer, reservation, new Observation(invocationId, provider, Source.HTTP, retryIndex,
                    status, outcome, elapsed(started), usage));
            throw failure;
        }
        if (capacity != null) capacity.responseReceived(response.getStatusCode().value());
        completed(journal, attempt, observer, reservation, new Observation(invocationId, provider, Source.HTTP, retryIndex,
                response.getStatusCode().value(), response.getStatusCode().isError()
                        ? Outcome.HTTP_ERROR : Outcome.RESPONSE,
                elapsed(started), inferenceUsage ? usage(json, provider, response.getBody()) : UNKNOWN));
        return response;
    }

    private static Attempt begin(Journal journal, String invocation, String provider, Source source, int retry,
            ProviderRateQuotas.Reservation reservation) {
        if (journal == null) return null;
        var attempt = new Attempt(reservation == null ? UUID.randomUUID().toString() : reservation.id(), invocation, provider, source, retry);
        try { journal.started(attempt); }
        catch (RuntimeException failure) { throw new JournalStartException(); }
        return attempt;
    }
    private static void completed(Journal journal, Attempt attempt, Consumer<Observation> observer,
            ProviderRateQuotas.Reservation reservation, Observation result) {
        if (reservation != null && result.source() == Source.HTTP) {
            try { reservation.settle(result.usage()); }
            catch (RuntimeException failure) {
                LoggerFactory.getLogger(LlmTransportMeter.class)
                        .warn("LLM quota settlement was not confirmed; no quota compensation is attempted");
            }
        }
        if (journal != null) {
            try { journal.completed(attempt, result); }
            catch (RuntimeException failure) {
                LoggerFactory.getLogger(LlmTransportMeter.class)
                        .warn("LLM attempt completion not recorded; its durable start remains unresolved");
            }
        }
        if (observer != null) emit(observer, result);
    }
    private static ProviderRateQuotas.Reservation quota() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.reservation;
    }

    private static ProviderConcurrencyPermits.Permit capacity() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.capacity;
    }

    private static Journal journal() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.journal;
    }

    private static long elapsed(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static Consumer<Observation> observer() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.observer;
    }

    private static void emit(Consumer<Observation> observer, Observation event) {
        try { observer.accept(event); }
        catch (RuntimeException failure) {
            // Do not log exception messages: an observer may have included sensitive context.
            LoggerFactory.getLogger(LlmTransportMeter.class)
                    .warn("LLM transport observation was not accepted; provider outcome is unchanged");
        }
    }

    private static Usage usage(ObjectMapper json, String provider, String body) {
        if (body == null || body.isBlank()) return UNKNOWN;
        try {
            JsonNode root = json.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            if (root == null || !root.isObject()) return INVALID;
            boolean gemini = "GEMINI".equals(provider);
            boolean responses = "response".equals(root.path("object").asText());
            JsonNode metadata = root.get(gemini ? "usageMetadata" : "usage");
            if (metadata == null || metadata.isNull()) return UNKNOWN;
            if (!metadata.isObject()) return INVALID;
            var fields = new TokenFields();
            Long input = fields.read(metadata.get(gemini ? "promptTokenCount" : responses ? "input_tokens" : "prompt_tokens"));
            Long output = fields.read(metadata.get(gemini ? "candidatesTokenCount" : responses ? "output_tokens" : "completion_tokens"));
            Long total = fields.read(metadata.get(gemini ? "totalTokenCount" : "total_tokens"));
            Long cached = gemini ? fields.read(metadata.get("cachedContentTokenCount"))
                    : fields.detail(metadata, responses ? "input_tokens_details" : "prompt_tokens_details", "cached_tokens");
            Long reasoning = gemini ? fields.read(metadata.get("thoughtsTokenCount"))
                    : fields.detail(metadata, responses ? "output_tokens_details" : "completion_tokens_details", "reasoning_tokens");
            return new Usage(input, output, total, cached, reasoning, fields.invalid);
        } catch (RuntimeException invalid) {
            return INVALID;
        }
    }

    private static final class TokenFields {
        private boolean invalid;
        private Long read(JsonNode value) {
            if (value == null || value.isNull()) return null;
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                invalid = true;
                return null;
            }
            return value.longValue();
        }
        private Long detail(JsonNode parent, String field, String key) {
            JsonNode value = parent.get(field);
            if (value == null || value.isNull()) return null;
            if (!value.isObject()) { invalid = true; return null; }
            return read(value.get(key));
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous = CURRENT.get();
        private final Thread owner = Thread.currentThread();
        private final Consumer<Observation> observer;
        private final Journal journal;
        private final ProviderRateQuotas.Reservation reservation;
        private final ProviderConcurrencyPermits.Permit capacity;
        private boolean closed;
        private Scope(Consumer<Observation> observer, Journal journal, ProviderRateQuotas.Reservation reservation,
                      ProviderConcurrencyPermits.Permit capacity) {
            this.observer = observer; this.journal = journal; this.reservation = reservation;
            this.capacity = capacity; CURRENT.set(this);
        }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Meter scope closed on another thread");
            if (closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Meter scopes must close in nesting order");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
