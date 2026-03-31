package com.taxonomy.analysis.service;

/**
 * Thrown when an LLM provider request times out (e.g. {@code SocketTimeoutException}
 * wrapped in a Spring {@code ResourceAccessException}).
 */
public class LlmTimeoutException extends RuntimeException {

    public LlmTimeoutException(String message) {
        super(message);
    }

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
