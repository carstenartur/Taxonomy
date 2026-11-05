package com.taxonomy.dto;

/**
 * AI availability level for the {@code GET /api/ai-status} endpoint.
 *
 * <ul>
 *   <li>{@link #FULL} – a generative provider is configured; for a custom compatible
 *       provider its server has also responded to the connectivity check.</li>
 *   <li>{@link #LIMITED} – only the local ONNX embedding model is available (no API key);
 *       cosine-similarity scoring works but no LLM reasoning or explanations.</li>
 *   <li>{@link #UNAVAILABLE} – configuration is missing or the checked server cannot
 *       be reached or rejects authentication.</li>
 *   <li>{@link #UNKNOWN} – configuration exists, but server availability could not be verified.</li>
 * </ul>
 */
public enum AiAvailabilityLevel {
    FULL,
    LIMITED,
    UNAVAILABLE,
    UNKNOWN
}
