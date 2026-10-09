package com.taxonomy.extension.api.llm;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Typed provider failure. Messages must be redacted; no response bodies or credentials. */
public final class LlmTransportException extends RuntimeException {
    public enum FailureKind { RATE_LIMIT, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE }

    private final FailureKind kind;
    private final Duration retryAfter;

    public LlmTransportException(FailureKind kind, String redactedMessage) {
        this(kind, redactedMessage, null);
    }

    public LlmTransportException(FailureKind kind, String redactedMessage, Duration retryAfter) {
        super(redactedMessage);
        this.kind = Objects.requireNonNull(kind, "kind");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("Retry delay must not be negative");
        }
        this.retryAfter = retryAfter;
    }

    public FailureKind kind() { return kind; }
    public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }
}
