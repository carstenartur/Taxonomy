package com.nato.taxonomy.service;

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
    /** Local embedding model (all-MiniLM-L6-v2) via DJL / ONNX Runtime. No API key required. */
    LOCAL_ONNX
}
