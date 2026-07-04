package com.taxonomy.analysis.service;

/**
 * Supported LLM providers for AI-based taxonomy analysis.
 */
public enum LlmProvider {
    GEMINI,
    OPENAI,
    DEEPSEEK,
    QWEN,
    LLAMA,
    MISTRAL,
    /** Local embedding model (bge-small-en-v1.5) via DJL / ONNX Runtime. No API key required. */
    LOCAL_ONNX
}
