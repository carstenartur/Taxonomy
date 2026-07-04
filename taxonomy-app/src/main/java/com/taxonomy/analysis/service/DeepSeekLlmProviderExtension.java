package com.taxonomy.analysis.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link LlmProviderExtension} for DeepSeek.
 */
@Component
public class DeepSeekLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "DEEPSEEK",
            "DeepSeek",
            true,
            false,
            false,
            false,
            List.of("deepseek.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.DEEPSEEK;
    }
}
