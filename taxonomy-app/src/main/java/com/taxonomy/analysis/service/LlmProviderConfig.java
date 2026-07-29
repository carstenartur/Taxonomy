package com.taxonomy.analysis.service;

import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.shared.service.LocalEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
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

    /**
     * Internal transport marker used to distinguish an intentionally unauthenticated custom
     * endpoint from a missing provider configuration. It is never sent as an HTTP header and
     * must never be presented as a configured credential in diagnostics.
     */
    static final String CUSTOM_NO_AUTH_API_KEY = "__taxonomy_custom_no_auth__";

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

    @Value("${custom.llm.url:}")
    private String customLlmUrl;

    @Value("${custom.llm.model:}")
    private String customLlmModel;

    @Value("${custom.llm.api.key:}")
    private String customLlmApiKey;

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
     *   <li>Auto-detect from available API keys or a complete custom endpoint configuration</li>
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

        if (hasText(geminiApiKey))  return LlmProvider.GEMINI;
        if (hasText(openaiApiKey))  return LlmProvider.OPENAI;
        if (hasText(deepseekApiKey)) return LlmProvider.DEEPSEEK;
        if (hasText(qwenApiKey))    return LlmProvider.QWEN;
        if (hasText(llamaApiKey))   return LlmProvider.LLAMA;
        if (hasText(mistralApiKey)) return LlmProvider.MISTRAL;
        if (isCustomOpenAiConfigured()) return LlmProvider.CUSTOM_OPENAI;

        return LlmProvider.GEMINI;
    }

    /** Returns a human-readable name for the active provider. */
    public String getActiveProviderName() {
        if (llmMock) return "Mock";
        return switch (getActiveProvider()) {
            case GEMINI        -> "Gemini";
            case OPENAI        -> "OpenAI";
            case DEEPSEEK      -> "DeepSeek";
            case QWEN          -> "Qwen";
            case LLAMA         -> "Llama";
            case MISTRAL       -> "Mistral";
            case CUSTOM_OPENAI -> "Custom OpenAI-compatible";
            case LOCAL_ONNX    -> "Local (bge-small-en-v1.5)";
        };
    }

    /**
     * Returns the list of currently available providers.
     * {@code LOCAL_ONNX} is always included because it may become available after model loading.
     */
    public List<String> getAvailableProviders() {
        List<String> providers = new ArrayList<>();
        providers.add("LOCAL_ONNX");
        if (hasText(geminiApiKey)) providers.add("GEMINI");
        if (hasText(openaiApiKey)) providers.add("OPENAI");
        if (hasText(deepseekApiKey)) providers.add("DEEPSEEK");
        if (hasText(qwenApiKey)) providers.add("QWEN");
        if (hasText(llamaApiKey)) providers.add("LLAMA");
        if (hasText(mistralApiKey)) providers.add("MISTRAL");
        if (isCustomOpenAiConfigured()) providers.add("CUSTOM_OPENAI");
        return providers;
    }

    /**
     * Returns the API key used by the HTTP gateway.
     *
     * <p>{@link LlmProvider#CUSTOM_OPENAI} supports endpoints without authentication. For such
     * endpoints this method returns an internal marker that passes the existing provider-agnostic
     * readiness check; {@link OpenAiCompatibleGateway} removes that marker and sends no
     * {@code Authorization} header.
     */
    public String getApiKey(LlmProvider provider) {
        return switch (provider) {
            case GEMINI        -> geminiApiKey;
            case OPENAI        -> openaiApiKey;
            case DEEPSEEK      -> deepseekApiKey;
            case QWEN          -> qwenApiKey;
            case LLAMA         -> llamaApiKey;
            case MISTRAL       -> mistralApiKey;
            case CUSTOM_OPENAI -> customOpenAiTransportApiKey();
            case LOCAL_ONNX    -> null;
        };
    }

    /** Returns whether a real credential, rather than the no-auth transport marker, is configured. */
    public boolean hasConfiguredApiKey(LlmProvider provider) {
        return switch (provider) {
            case GEMINI        -> hasText(geminiApiKey);
            case OPENAI        -> hasText(openaiApiKey);
            case DEEPSEEK      -> hasText(deepseekApiKey);
            case QWEN          -> hasText(qwenApiKey);
            case LLAMA         -> hasText(llamaApiKey);
            case MISTRAL       -> hasText(mistralApiKey);
            case CUSTOM_OPENAI -> hasText(customLlmApiKey);
            case LOCAL_ONNX    -> false;
        };
    }

    /** Returns the API endpoint URL for an OpenAI-compatible provider. */
    public String getOpenAiCompatibleUrl(LlmProvider provider) {
        return switch (provider) {
            case OPENAI        -> OPENAI_URL;
            case DEEPSEEK      -> DEEPSEEK_URL;
            case QWEN          -> QWEN_URL;
            case LLAMA         -> LLAMA_URL;
            case MISTRAL       -> MISTRAL_URL;
            case CUSTOM_OPENAI -> trimToEmpty(customLlmUrl);
            default -> throw new IllegalArgumentException("Not an OpenAI-compatible provider: " + provider);
        };
    }

    /** Returns the model name for an OpenAI-compatible provider. */
    public String getOpenAiCompatibleModel(LlmProvider provider) {
        return switch (provider) {
            case OPENAI        -> OPENAI_MODEL;
            case DEEPSEEK      -> DEEPSEEK_MODEL;
            case QWEN          -> QWEN_MODEL;
            case LLAMA         -> LLAMA_MODEL;
            case MISTRAL       -> MISTRAL_MODEL;
            case CUSTOM_OPENAI -> trimToEmpty(customLlmModel);
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    /** Returns the Gemini endpoint URL, including the query-parameter key placeholder. */
    public String getGeminiUrl() {
        return GEMINI_URL;
    }

    /**
     * Returns whether the custom endpoint is a complete OpenAI-compatible Chat Completions URL
     * and has a model name. The URL is deliberately not written to logs because operator mistakes
     * can place credentials in user-info or query components.
     */
    public boolean isCustomOpenAiConfigured() {
        if (!hasText(customLlmUrl) || !hasText(customLlmModel)) return false;
        try {
            URI uri = URI.create(customLlmUrl.trim());
            String scheme = uri.getScheme();
            String path = uri.getPath();
            return uri.getHost() != null
                    && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && path != null
                    && path.endsWith("/chat/completions");
        } catch (IllegalArgumentException e) {
            log.warn("Invalid custom LLM URL configuration: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Returns whether all mandatory configuration for the given runtime provider is present. */
    public boolean isProviderConfigured(LlmProvider provider) {
        return switch (provider) {
            case GEMINI        -> hasText(geminiApiKey);
            case OPENAI        -> hasText(openaiApiKey);
            case DEEPSEEK      -> hasText(deepseekApiKey);
            case QWEN          -> hasText(qwenApiKey);
            case LLAMA         -> hasText(llamaApiKey);
            case MISTRAL       -> hasText(mistralApiKey);
            case CUSTOM_OPENAI -> isCustomOpenAiConfigured();
            case LOCAL_ONNX    -> localEmbeddingService.isAvailable();
        };
    }

    // ── Availability checks ───────────────────────────────────────────────────

    /** Returns {@code true} if at least one real cloud/custom API key is configured. */
    public boolean hasAnyCloudApiKey() {
        return hasText(geminiApiKey)
            || hasText(openaiApiKey)
            || hasText(deepseekApiKey)
            || hasText(qwenApiKey)
            || hasText(llamaApiKey)
            || hasText(mistralApiKey)
            || hasText(customLlmApiKey);
    }

    /** Returns {@code true} if at least one HTTP-based provider can be called. */
    public boolean hasAnyHttpProviderConfiguration() {
        return hasText(geminiApiKey)
            || hasText(openaiApiKey)
            || hasText(deepseekApiKey)
            || hasText(qwenApiKey)
            || hasText(llamaApiKey)
            || hasText(mistralApiKey)
            || isCustomOpenAiConfigured();
    }

    /**
     * Returns the three-state availability level for the active provider.
     *
     * <ul>
     *   <li>{@link AiAvailabilityLevel#FULL}: mock mode or a fully configured HTTP provider.</li>
     *   <li>{@link AiAvailabilityLevel#LIMITED}: local embedding is selected/available.</li>
     *   <li>{@link AiAvailabilityLevel#UNAVAILABLE}: mandatory provider configuration is missing.</li>
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
        if (isProviderConfigured(provider)) return AiAvailabilityLevel.FULL;
        return localEmbeddingService.isAvailable()
                ? AiAvailabilityLevel.LIMITED
                : AiAvailabilityLevel.UNAVAILABLE;
    }

    /** Returns whether either a full or limited AI capability is currently available. */
    public boolean isAvailable() {
        return getAvailabilityLevel() != AiAvailabilityLevel.UNAVAILABLE;
    }

    /** Returns {@code true} when mock mode is enabled via {@code llm.mock=true}. */
    public boolean isMockMode() {
        return llmMock;
    }

    private String customOpenAiTransportApiKey() {
        if (!isCustomOpenAiConfigured()) return "";
        return hasText(customLlmApiKey) ? customLlmApiKey : CUSTOM_NO_AUTH_API_KEY;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
