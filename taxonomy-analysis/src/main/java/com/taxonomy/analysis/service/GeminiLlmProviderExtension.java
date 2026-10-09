package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider metadata adapter for Google Gemini. */
@Component
public class GeminiLlmProviderExtension extends BuiltinLlmTransportExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "GEMINI", "Gemini", true, false, false, false,
            List.of("gemini.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
