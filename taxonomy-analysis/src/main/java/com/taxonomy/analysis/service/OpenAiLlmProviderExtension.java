package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider metadata adapter for OpenAI. */
@Component
public class OpenAiLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "OPENAI", "OpenAI", true, false, false, false,
            List.of("openai.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
