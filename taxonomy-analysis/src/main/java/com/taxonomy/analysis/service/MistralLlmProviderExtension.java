package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider metadata adapter for Mistral. */
@Component
public class MistralLlmProviderExtension extends BuiltinLlmTransportExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "MISTRAL", "Mistral", true, false, false, false,
            List.of("mistral.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
