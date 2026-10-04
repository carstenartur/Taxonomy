package com.taxonomy.analysis.service;

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
    Permit acquire(LlmProvider provider, Runnable checkpoint);

    /** Thread-confined, idempotent return of the capacity held by one HTTP attempt. */
    @FunctionalInterface
    interface Permit extends AutoCloseable {
        @Override void close();
    }

    /** Failed-closed admission; no physical provider attempt may follow this failure. */
    final class UnavailableException extends LlmRateLimitException {
        public UnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
