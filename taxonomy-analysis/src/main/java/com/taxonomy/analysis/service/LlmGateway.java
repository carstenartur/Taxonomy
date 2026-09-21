package com.taxonomy.analysis.service;

/**
 * Abstraction for LLM provider HTTP communication.
 *
 * <p>Each implementation encapsulates provider-specific request formatting,
 * response parsing, throttling (RPM rate-limiting), and error handling.
 * This allows different providers to have independent rate limits instead of
 * sharing a single throttle.
 *
 * @see GeminiGateway
 * @see OpenAiCompatibleGateway
 * @see LlmGatewayRegistry
 */
public interface LlmGateway {

    /**
     * Sends a prompt to the LLM provider and returns the raw API response body.
     *
     * <p>Implementations handle provider-specific request formatting, per-gateway
     * throttling, timeout configuration, and recording/replay integration.
     *
     * @param prompt the LLM prompt text
     * @param apiKey the API key for authentication
     * @return the raw API response body, or {@code null} on error
     * @throws LlmRateLimitException if the provider returns a rate-limit response (HTTP 429)
     */
    String sendHttpRequest(String prompt, String apiKey);

    /**
     * Validates a fully assembled prompt without I/O, quota consumption or recording.
     * Production gateways override this at their existing final-budget boundary;
     * sending still validates again. Unbounded transports retain the default.
     */
    default void validatePromptBudget(String prompt) { }


    /**
     * Extracts the usable text content from the provider-specific raw response body.
     *
     * @param rawResponseBody the raw JSON response body from the provider API
     * @return the extracted text, or {@code null} if extraction failed
     */
    String extractResponseText(String rawResponseBody);

    /**
     * Returns the provider name for logging and diagnostics.
     */
    String providerName();
}
