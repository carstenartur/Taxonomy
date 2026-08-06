package com.taxonomy.analysis.service;

/**
 * Actionable failure raised by an LLM gateway when the provider configuration or
 * endpoint can be identified as the cause. Unlike an empty provider response, this
 * exception is intentionally propagated to API diagnostics and analysis details.
 */
public class LlmProviderException extends RuntimeException {

    public enum Reason {
        CONFIGURATION,
        AUTHENTICATION,
        ENDPOINT_UNREACHABLE,
        REQUEST_REJECTED
    }

    private final Reason reason;

    public LlmProviderException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public LlmProviderException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
