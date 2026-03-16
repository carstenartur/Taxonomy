package com.taxonomy.analysis.service;

/**
 * Thrown when an LLM provider returns an HTTP 429 (Too Many Requests) response
 * or signals quota exhaustion in the response body (e.g. Gemini RESOURCE_EXHAUSTED).
 */
public class LlmRateLimitException extends RuntimeException {

    public LlmRateLimitException(String message) {
        super(message);
    }

    public LlmRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
