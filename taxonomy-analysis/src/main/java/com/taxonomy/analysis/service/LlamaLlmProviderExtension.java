package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider metadata adapter for Llama. */
@Component
public class LlamaLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "LLAMA", "Llama", true, false, false, false,
            List.of("llama.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
