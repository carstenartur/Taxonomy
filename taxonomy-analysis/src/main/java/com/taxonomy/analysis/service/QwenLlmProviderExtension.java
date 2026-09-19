package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider metadata adapter for Alibaba Cloud Qwen. */
@Component
public class QwenLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "QWEN", "Qwen", true, false, false, false,
            List.of("qwen.api.key"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
