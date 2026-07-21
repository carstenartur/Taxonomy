package com.taxonomy.extension.api.llm;

import java.io.Serializable;
import java.util.List;

/** Serializable capabilities and configuration metadata for an LLM provider. */
public record LlmProviderDescriptor(
        String providerId,
        String providerName,
        boolean requiresApiKey,
        boolean supportsStreaming,
        boolean supportsStructuredOutput,
        boolean supportsLocalExecution,
        List<String> configurationProperties) implements Serializable {

    public LlmProviderDescriptor {
        configurationProperties = configurationProperties == null
                ? List.of() : List.copyOf(configurationProperties);
    }
}
