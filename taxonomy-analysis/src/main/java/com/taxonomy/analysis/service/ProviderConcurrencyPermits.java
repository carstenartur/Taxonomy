package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.ProviderId;

/**
 * Transport-neutral admission for one physical provider HTTP attempt. Implementations
 * retain no prompts, credentials or operation payloads. This is concurrency capacity,
 * not an RPM/TPM/RPD policy; callers retain their process-local rate limiter.
 */
@FunctionalInterface
public interface ProviderConcurrencyPermits {
    ProviderConcurrencyPermits NONE = (provider, checkpoint) -> {
        checkpoint.run();
        return () -> {};
    };

    /** Wait cooperatively, invoking the supplied cancellation/deadline checkpoint. */
    Permit acquire(ProviderId provider, Runnable checkpoint);

    /** Existing built-in callers retain their aliases. */
    default Permit acquire(LlmProvider provider, Runnable checkpoint) {
        return acquire(provider.id(), checkpoint);
    }

    /**
     * Thread-confined lifecycle of one physical attempt. close() releases unused or
     * proven-complete capacity; it must retain uncertain in-flight capacity.
     */
    @FunctionalInterface
    interface Permit extends AutoCloseable {
        /** Must confirm durable ownership before the admitted request can run. */
        default void beforeTransport() { }

        /** A complete HTTP response was received, not merely headers or a local timeout. */
        default void responseReceived(int statusCode) { }

        @Override void close();
    }

    /**
     * Observe local lease closure without dropping lifecycle hooks. A successful
     * close is not proof that uncertain capacity was returned to the broker.
     * The callback runs once even when the delegate's close fails.
     */
    static Permit observeClose(Permit delegate, java.util.function.Consumer<Boolean> afterClose) {
        java.util.Objects.requireNonNull(delegate, "delegate");
        java.util.Objects.requireNonNull(afterClose, "afterClose");
        return new Permit() {
            private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            @Override public void beforeTransport() { delegate.beforeTransport(); }
            @Override public void responseReceived(int statusCode) { delegate.responseReceived(statusCode); }
            @Override public void close() {
                if (!closed.compareAndSet(false, true)) return;
                boolean completed = false;
                try { delegate.close(); completed = true; }
                finally { afterClose.accept(completed); }
            }
        };
    }

    /** Failed-closed admission; no physical provider attempt may follow this failure. */
    final class UnavailableException extends LlmRateLimitException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
