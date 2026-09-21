package com.taxonomy.analysis.service;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Scoped observation contract. Gateway emission is implemented separately from this binding. */
public final class LlmTransportMeter {
    public enum Source { HTTP, RECORDING_REPLAY }
    public enum Outcome { RESPONSE, HTTP_ERROR, TRANSPORT_ERROR }
    public record Usage(Long inputTokens, Long outputTokens, Long totalTokens,
                        Long cachedInputTokens, Long reasoningTokens, boolean invalid) {}
    public record Observation(String invocationId, String provider, Source source, int retryIndex,
                              Integer statusCode, Outcome outcome, long durationMillis, Usage usage) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private LlmTransportMeter() {}

    public static Scope open(Consumer<Observation> observer) {
        return new Scope(Objects.requireNonNull(observer, "observer"));
    }

    /** Explicit capture only: pooled threads do not inherit another request's observer. */
    public static <T> Supplier<T> capture(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        Scope scope = CURRENT.get();
        Consumer<Observation> observer = scope == null ? null : scope.observer;
        return () -> {
            try (var ignored = new Scope(observer)) { return operation.get(); }
        };
    }

    public static final class Scope implements AutoCloseable {
        private final Scope previous = CURRENT.get();
        private final Thread owner = Thread.currentThread();
        private final Consumer<Observation> observer;
        private boolean closed;
        private Scope(Consumer<Observation> observer) { this.observer = observer; CURRENT.set(this); }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Meter scope closed on another thread");
            if (closed) return;
            if (CURRENT.get() != this) throw new IllegalStateException("Meter scopes must close in nesting order");
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
