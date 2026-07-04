package com.taxonomy.analysis.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link LlmProviderExtension} for Alibaba Cloud Qwen.
 */
@Component
public class QwenLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "QWEN",
            "Qwen",
            true,
            false,
            false,
            false,
            List.of("qwen.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.QWEN;
    }
}
