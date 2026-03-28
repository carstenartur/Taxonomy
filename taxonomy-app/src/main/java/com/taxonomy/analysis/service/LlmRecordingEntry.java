package com.taxonomy.analysis.service;

/**
 * DTO for a single recorded LLM call, persisted as JSON in
 * {@code src/test/resources/llm-recordings/<hash>.json}.
 *
 * @param promptHash   SHA-256 hex string of the whitespace-normalised prompt
 * @param promptText   the original (un-normalised) prompt for human readability
 * @param responseBody the raw HTTP response body (not the extracted text) so
 *                     the full parsing path (extractGeminiText / extractOpenAiText) is exercised
 * @param provider     the LLM provider name (e.g. "GEMINI", "OPENAI")
 * @param recordedAt   ISO-8601 timestamp when the recording was captured
 * @param testOrigin   optional description of the test that produced this recording
 * @param lastUsed     ISO-8601 timestamp of the last replay — updated on every replay so stale
 *                     entries can be detected
 */
public record LlmRecordingEntry(
        String promptHash,
        String promptText,
        String responseBody,
        String provider,
        String recordedAt,
        String testOrigin,
        String lastUsed
) {}
