package com.taxonomy.analysis.service;

/** Supported runtime LLM providers for AI-based taxonomy analysis. */
public enum LlmProvider {
    GEMINI,
    OPENAI,
    DEEPSEEK,
    QWEN,
    LLAMA,
    MISTRAL,
    /** Operator-configured OpenAI-compatible HTTP endpoint, including self-hosted models. */
    CUSTOM_OPENAI,
    /** Local embedding model via DJL / ONNX Runtime. No API key required. */
    LOCAL_ONNX;

    private final com.taxonomy.extension.api.llm.ProviderId id =
            new com.taxonomy.extension.api.llm.ProviderId(name());

    /** Backward-compatible built-in alias; the execution registry accepts open IDs. */
    public com.taxonomy.extension.api.llm.ProviderId id() { return id; }

    public static java.util.Optional<LlmProvider> builtin(com.taxonomy.extension.api.llm.ProviderId id) {
        return java.util.Arrays.stream(values()).filter(provider -> provider.id.equals(id)).findFirst();
    }

    public enum CompletionCapability { GENERATIVE_TEXT, EMBEDDINGS_ONLY }

    public CompletionCapability completionCapability() {
        return this == LOCAL_ONNX ? CompletionCapability.EMBEDDINGS_ONLY : CompletionCapability.GENERATIVE_TEXT;
    }
}
