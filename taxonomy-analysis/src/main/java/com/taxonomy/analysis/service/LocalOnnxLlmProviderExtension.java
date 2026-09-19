package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.LlmProviderDescriptor;
import com.taxonomy.extension.api.llm.LlmProviderExtension;
import org.springframework.stereotype.Component;

import java.util.List;

/** Provider metadata adapter for local ONNX execution. */
@Component
public class LocalOnnxLlmProviderExtension implements LlmProviderExtension {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "LOCAL_ONNX",
            "Local (bge-small-en-v1.5)",
            false,
            false,
            false,
            true,
            List.of("embedding.enabled", "embedding.model.dir", "embedding.model.name"));

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }
}
