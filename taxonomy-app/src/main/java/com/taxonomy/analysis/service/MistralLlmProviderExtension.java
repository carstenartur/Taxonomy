package com.taxonomy.analysis.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link LlmProviderExtension} for Mistral.
 */
@Component
public class MistralLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "MISTRAL",
            "Mistral",
            true,
            false,
            false,
            false,
            List.of("mistral.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.MISTRAL;
    }
}
