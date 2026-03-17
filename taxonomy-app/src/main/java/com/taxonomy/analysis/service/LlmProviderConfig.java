package com.taxonomy.analysis.service;

import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Centralises LLM provider configuration: API keys, endpoint URLs, model names,
 * per-request overrides, and provider-detection logic.
 *
 * <p>Extracted from {@link LlmService} to follow the Single Responsibility Principle.
 * {@code LlmService} now delegates all "which provider / what key / what URL" questions
 * to this class and focuses on the analysis orchestration and HTTP call logic.
 */
@Component
public class LlmProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);

    // ── Endpoint URLs ─────────────────────────────────────────────────────────

    static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=";

    static final String OPENAI_URL   = "https://api.openai.com/v1/chat/completions";
    static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    static final String QWEN_URL     = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    static final String LLAMA_URL    = "https://api.llama-api.com/chat/completions";
    static final String MISTRAL_URL  = "https://api.mistral.ai/v1/chat/completions";

    // ── Default model names ───────────────────────────────────────────────────

    static final String OPENAI_MODEL   = "gpt-4o-mini";
    static final String DEEPSEEK_MODEL = "deepseek-chat";
    static final String QWEN_MODEL     = "qwen-plus";
    static final String LLAMA_MODEL    = "llama3.1-70b";
    static final String MISTRAL_MODEL  = "mistral-small-latest";

    // ── Per-request override ──────────────────────────────────────────────────

    private static final ThreadLocal<LlmProvider> requestProviderOverride = new ThreadLocal<>();

    // ── Injected configuration ────────────────────────────────────────────────

    @Value("${llm.provider:}")
    private String llmProviderConfig;

    @Value("${llm.mock:false}")
    private boolean llmMock;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    @Value("${openai.api.key:}")
    private String openaiApiKey;

    @Value("${deepseek.api.key:}")
    private String deepseekApiKey;

    @Value("${qwen.api.key:}")
    private String qwenApiKey;

    @Value("${llama.api.key:}")
    private String llamaApiKey;

    @Value("${mistral.api.key:}")
    private String mistralApiKey;

    private final LocalEmbeddingService localEmbeddingService;

    public LlmProviderConfig(LocalEmbeddingService localEmbeddingService) {
        this.localEmbeddingService = localEmbeddingService;
    }

    // ── Per-request override API ──────────────────────────────────────────────

    /** Sets a per-request provider override (call from controller before analysis). */
    public void setRequestProvider(LlmProvider provider) {
        requestProviderOverride.set(provider);
    }

    /** Clears the per-request provider override (call in finally block). */
    public void clearRequestProvider() {
        requestProviderOverride.remove();
    }

    // ── Provider detection ────────────────────────────────────────────────────

    /**
     * Returns the active provider based on the priority chain.
     *
     * <ol>
     *   <li>Per-request override via {@link #setRequestProvider}</li>
     *   <li>Explicit {@code llm.provider} config / {@code LLM_PROVIDER} env var</li>
     *   <li>Auto-detect from available API keys</li>
     *   <li>Default: {@link LlmProvider#GEMINI}</li>
     * </ol>
     */
    public LlmProvider getActiveProvider() {
        LlmProvider override = requestProviderOverride.get();
        if (override != null) return override;

        if (llmProviderConfig != null && !llmProviderConfig.isBlank()) {
            try {
                return LlmProvider.valueOf(llmProviderConfig.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown LLM provider '{}' in config; falling back to auto-detect",
                        llmProviderConfig);
            }
        }

        if (geminiApiKey  != null && !geminiApiKey.isBlank())  return LlmProvider.GEMINI;
        if (openaiApiKey  != null && !openaiApiKey.isBlank())  return LlmProvider.OPENAI;
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) return LlmProvider.DEEPSEEK;
        if (qwenApiKey    != null && !qwenApiKey.isBlank())    return LlmProvider.QWEN;
        if (llamaApiKey   != null && !llamaApiKey.isBlank())   return LlmProvider.LLAMA;
        if (mistralApiKey != null && !mistralApiKey.isBlank()) return LlmProvider.MISTRAL;

        return LlmProvider.GEMINI;
    }

    /**
     * Returns a human-readable name for the active provider (e.g. "Gemini", "OpenAI").
     */
    public String getActiveProviderName() {
        if (llmMock) return "Mock";
        return switch (getActiveProvider()) {
            case GEMINI      -> "Gemini";
            case OPENAI      -> "OpenAI";
            case DEEPSEEK    -> "DeepSeek";
            case QWEN        -> "Qwen";
            case LLAMA       -> "Llama";
            case MISTRAL     -> "Mistral";
            case LOCAL_ONNX  -> "Local (bge-small-en-v1.5)";
        };
    }

    /**
     * Returns the list of currently available providers.
     * {@code LOCAL_ONNX} is always included (no API key required).
     * Cloud providers are included only when their API key is configured.
     */
    public List<String> getAvailableProviders() {
        List<String> providers = new ArrayList<>();
        providers.add("LOCAL_ONNX");
        if (geminiApiKey  != null && !geminiApiKey.isBlank())  providers.add("GEMINI");
        if (openaiApiKey  != null && !openaiApiKey.isBlank())  providers.add("OPENAI");
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) providers.add("DEEPSEEK");
        if (qwenApiKey    != null && !qwenApiKey.isBlank())    providers.add("QWEN");
        if (llamaApiKey   != null && !llamaApiKey.isBlank())   providers.add("LLAMA");
        if (mistralApiKey != null && !mistralApiKey.isBlank()) providers.add("MISTRAL");
        return providers;
    }

    /**
     * Returns the API key for the given provider, or {@code null} for
     * {@link LlmProvider#LOCAL_ONNX} which requires no key.
     */
    public String getApiKey(LlmProvider provider) {
        return switch (provider) {
            case GEMINI      -> geminiApiKey;
            case OPENAI      -> openaiApiKey;
            case DEEPSEEK    -> deepseekApiKey;
            case QWEN        -> qwenApiKey;
            case LLAMA       -> llamaApiKey;
            case MISTRAL     -> mistralApiKey;
            case LOCAL_ONNX  -> null;
        };
    }

    /**
     * Returns the API endpoint URL for the given provider.
     *
     * @throws IllegalArgumentException for {@link LlmProvider#LOCAL_ONNX} or
     *         {@link LlmProvider#GEMINI} (which uses a different call pattern)
     */
    public String getOpenAiCompatibleUrl(LlmProvider provider) {
        return switch (provider) {
            case OPENAI   -> OPENAI_URL;
            case DEEPSEEK -> DEEPSEEK_URL;
            case QWEN     -> QWEN_URL;
            case LLAMA    -> LLAMA_URL;
            case MISTRAL  -> MISTRAL_URL;
            default -> throw new IllegalArgumentException("Not an OpenAI-compatible provider: " + provider);
        };
    }

    /**
     * Returns the default model name for the given OpenAI-compatible provider.
     *
     * @throws IllegalArgumentException for non-OpenAI-compatible providers
     */
    public String getOpenAiCompatibleModel(LlmProvider provider) {
        return switch (provider) {
            case OPENAI   -> OPENAI_MODEL;
            case DEEPSEEK -> DEEPSEEK_MODEL;
            case QWEN     -> QWEN_MODEL;
            case LLAMA    -> LLAMA_MODEL;
            case MISTRAL  -> MISTRAL_MODEL;
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    /** Returns the Gemini endpoint URL (including the query-parameter key placeholder). */
    public String getGeminiUrl() {
        return GEMINI_URL;
    }

    // ── Availability checks ───────────────────────────────────────────────────

    /** Returns {@code true} if at least one cloud LLM API key is configured. */
    public boolean hasAnyCloudApiKey() {
        return (geminiApiKey   != null && !geminiApiKey.isBlank())
            || (openaiApiKey   != null && !openaiApiKey.isBlank())
            || (deepseekApiKey != null && !deepseekApiKey.isBlank())
            || (qwenApiKey     != null && !qwenApiKey.isBlank())
            || (llamaApiKey    != null && !llamaApiKey.isBlank())
            || (mistralApiKey  != null && !mistralApiKey.isBlank());
    }

    /**
     * Returns the three-state availability level.
     *
     * <ul>
     *   <li>{@link AiAvailabilityLevel#FULL} – mock mode active, or a cloud
     *       provider with a configured API key is the active provider.</li>
     *   <li>{@link AiAvailabilityLevel#LIMITED} – either the active provider
     *       is explicitly set to {@link LlmProvider#LOCAL_ONNX}, or no cloud API key is
     *       configured and the local embedding model loaded successfully (implicit fallback).</li>
     *   <li>{@link AiAvailabilityLevel#UNAVAILABLE} – no API key configured
     *       and the local embedding model is not available.</li>
     * </ul>
     */
    public AiAvailabilityLevel getAvailabilityLevel() {
        if (llmMock) return AiAvailabilityLevel.FULL;
        LlmProvider provider = getActiveProvider();
        if (provider == LlmProvider.LOCAL_ONNX) {
            return localEmbeddingService.isAvailable()
                    ? AiAvailabilityLevel.LIMITED
                    : AiAvailabilityLevel.UNAVAILABLE;
        }
        if (hasAnyCloudApiKey()) return AiAvailabilityLevel.FULL;
        return localEmbeddingService.isAvailable()
                ? AiAvailabilityLevel.LIMITED
                : AiAvailabilityLevel.UNAVAILABLE;
    }

    /**
     * Returns {@code true} if at least one provider has a configured API key,
     * or if the active provider is {@link LlmProvider#LOCAL_ONNX} (which requires no key),
     * or if mock mode is active.
     */
    public boolean isAvailable() {
        return getAvailabilityLevel() != AiAvailabilityLevel.UNAVAILABLE;
    }

    /** Returns {@code true} when mock mode is enabled via {@code llm.mock=true}. */
    public boolean isMockMode() {
        return llmMock;
    }
}
