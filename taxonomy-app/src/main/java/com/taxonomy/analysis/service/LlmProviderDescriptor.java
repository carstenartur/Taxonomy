package com.taxonomy.analysis.service;

import java.io.Serializable;
import java.util.List;

/**
 * Serializable descriptor for an LLM provider extension.
 *
 * @param providerId               unique provider identifier matching {@link LlmProvider#name()} (e.g. {@code "GEMINI"})
 * @param providerName             human-readable provider name (e.g. {@code "Gemini"})
 * @param requiresApiKey           {@code true} if an API key must be configured to use this provider
 * @param supportsStreaming         {@code true} if the provider supports streaming responses
 * @param supportsStructuredOutput {@code true} if the provider supports structured (JSON-schema) output
 * @param supportsLocalExecution   {@code true} if the provider can run fully locally without network access
 * @param configurationProperties  list of Spring property keys required to configure this provider
 *                                 (e.g. {@code ["gemini.api.key"]})
 */
public record LlmProviderDescriptor(
        String providerId,
        String providerName,
        boolean requiresApiKey,
        boolean supportsStreaming,
        boolean supportsStructuredOutput,
        boolean supportsLocalExecution,
        List<String> configurationProperties) implements Serializable {
}
