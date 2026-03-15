package com.taxonomy.dto;

/**
 * Three-state AI availability level for the {@code GET /api/ai-status} endpoint.
 *
 * <ul>
 *   <li>{@link #FULL} – a cloud LLM provider with a configured API key is active;
 *       full reasoning/explanation capabilities are available.</li>
 *   <li>{@link #LIMITED} – only the local ONNX embedding model is available (no API key);
 *       cosine-similarity scoring works but no LLM reasoning or explanations.</li>
 *   <li>{@link #UNAVAILABLE} – neither an API key nor a functioning local ONNX model
 *       is available; AI features are disabled.</li>
 * </ul>
 */
public enum AiAvailabilityLevel {
    FULL,
    LIMITED,
    UNAVAILABLE
}
