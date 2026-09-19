package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider metadata adapter for an operator-configured OpenAI-compatible endpoint. */
@Component
public class CustomOpenAiLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "CUSTOM_OPENAI", "Custom OpenAI-compatible", false, false, false, true,
            List.of("custom.llm.url", "custom.llm.model", "custom.llm.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
