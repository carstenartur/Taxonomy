package com.taxonomy.analysis.service;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link LlmProviderExtension} for local ONNX embedding (bge-small-en-v1.5 via DJL).
 * No API key is required; the model runs entirely on the local machine.
 */
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

    @Override
    public LlmProvider provider() {
        return LlmProvider.LOCAL_ONNX;
    }
}
