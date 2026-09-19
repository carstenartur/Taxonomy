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
    LOCAL_ONNX
}
