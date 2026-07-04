package com.taxonomy.analysis.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link LlmProviderExtension} for Google Gemini.
 */
@Component
public class GeminiLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "GEMINI",
            "Gemini",
            true,
            false,
            false,
            false,
            List.of("gemini.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.GEMINI;
    }
}
