package com.taxonomy.analysis.service;

import com.taxonomy.dto.AiAvailabilityLevel;
import com.taxonomy.extension.api.llm.ProviderId;
import com.taxonomy.extension.api.llm.LlmTransportExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import com.taxonomy.catalog.service.LocalEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Centralises LLM provider selection, credentials, endpoints and readiness diagnostics. */
@Component
public class LlmProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderConfig.class);

    static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent?key=";

    static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    static final String QWEN_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    static final String LLAMA_URL = "https://api.llama-api.com/chat/completions";
    static final String MISTRAL_URL = "https://api.mistral.ai/v1/chat/completions";

    /** Internal marker for an intentionally unauthenticated custom endpoint. Never sent as a header. */
    static final String CUSTOM_NO_AUTH_API_KEY = "__taxonomy_custom_no_auth__";

    static final String OPENAI_MODEL = "gpt-4o-mini";
    static final String DEEPSEEK_MODEL = "deepseek-chat";
    static final String QWEN_MODEL = "qwen-plus";
    static final String LLAMA_MODEL = "llama3.1-70b";
    static final String MISTRAL_MODEL = "mistral-small-latest";

    private static final ThreadLocal<ProviderId> requestProviderOverride = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> requestMockOverride = new ThreadLocal<>();
    private static final ThreadLocal<RequestProviderScope> providerScopes = new ThreadLocal<>();

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
    private Map<ProviderId, LlmTransportExtension> externalProviders = Map.of();
    private Environment environment;

    @Autowired
    void initializeExtensions(org.springframework.beans.factory.ObjectProvider<LlmTransportExtension> extensions,
                              Environment environment,
                              org.springframework.beans.factory.ObjectProvider<com.taxonomy.extension.api.plugin.ExtensionCatalog> catalogs) {
        var catalog = catalogs.getIfAvailable();
        if (catalog == null) {
            configureExtensions(extensions.orderedStream().filter(e -> !(e instanceof BuiltinLlmTransportExtension)).toList(), environment);
        } else {
            var leases = catalog.acquireAll(com.taxonomy.shared.extension.ExtensionKind.LLM_PROVIDER, LlmTransportExtension.class);
            try { configureExtensions(leases.stream().map(com.taxonomy.extension.api.plugin.ExtensionLease::extension)
                    .filter(e -> !(e instanceof BuiltinLlmTransportExtension)).toList(), environment); }
            finally { leases.forEach(com.taxonomy.extension.api.plugin.ExtensionLease::close); }
        }
    }

    void configureExtensions(List<LlmTransportExtension> extensions, Environment environment) {
        Map<ProviderId, LlmTransportExtension> validated = new LinkedHashMap<>();
        for (var extension : extensions) {
            ProviderId id = new ProviderId(extension.descriptor().providerId());
            if (LlmProvider.builtin(new ProviderId(id.value().replace('-', '_'))).isPresent()
                    || validated.putIfAbsent(id, extension) != null) {
                throw new IllegalStateException("Duplicate LLM provider ID: " + id);
            }
            if (extension.transport() == null
                    || !id.equals(new ProviderId(extension.transport().providerName()))) {
                throw new IllegalStateException("Provider transport identity mismatch: " + id);
            }
        }
        this.externalProviders = Map.copyOf(validated);
        this.environment = environment;
    }

    /** STARTUP-only transports have the lifetime of this application context. */
    List<LlmTransportExtension> externalTransports() { return List.copyOf(externalProviders.values()); }

    public ProviderId requireRegisteredProvider(String value) {
        ProviderId id = new ProviderId(value);
        if (LlmProvider.builtin(id).isEmpty() && !externalProviders.containsKey(id)) {
            throw new IllegalArgumentException("No executable LLM provider registered: " + id);
        }
        return id;
    }

    public List<ProviderId> registeredProviderIds() {
        var ids = new java.util.TreeSet<ProviderId>(java.util.Comparator.comparing(ProviderId::value));
        for (LlmProvider builtin : LlmProvider.values()) ids.add(builtin.id());
        ids.addAll(externalProviders.keySet());
        return List.copyOf(ids);
    }

    private String externalSetting(ProviderId id, String setting, String fallback) {
        requireRegisteredProvider(id.value());
        return environment == null ? fallback : environment.getProperty(
                "taxonomy.llm.providers." + id.value().toLowerCase(Locale.ROOT) + "." + setting, fallback);
    }

    public String getApiKey(ProviderId id) {
        var builtin = LlmProvider.builtin(id);
        return builtin.isPresent() ? getApiKey(builtin.get()) : externalSetting(id, "api-key", "");
    }

    public boolean requiresApiKey(ProviderId id) {
        var builtin = LlmProvider.builtin(id);
        if (builtin.isPresent()) return builtin.get() != LlmProvider.CUSTOM_OPENAI
                && builtin.get() != LlmProvider.LOCAL_ONNX;
        requireRegisteredProvider(id.value());
        return externalProviders.get(id).descriptor().requiresApiKey();
    }

    public boolean hasConfiguredApiKey(ProviderId id) {
        return LlmProvider.builtin(id).map(this::hasConfiguredApiKey)
                .orElseGet(() -> hasText(getApiKey(id)));
    }

    public String getOpenAiCompatibleUrl(ProviderId id) {
        return LlmProvider.builtin(id).map(this::getOpenAiCompatibleUrl)
                .orElseGet(() -> externalSetting(id, "endpoint-identity", "plugin:" + id.value()));
    }

    public String getOpenAiCompatibleModel(ProviderId id) {
        return LlmProvider.builtin(id).map(this::getOpenAiCompatibleModel)
                .orElseGet(() -> externalSetting(id, "model", id.value()));
    }

    public boolean isProviderConfigured(ProviderId id) {
        var builtin = LlmProvider.builtin(id);
        if (builtin.isPresent()) return isProviderConfigured(builtin.get());
        var extension = externalProviders.get(id);
        return extension != null && (!extension.descriptor().requiresApiKey() || hasText(getApiKey(id)));
    }

    public String getProviderConfigurationError(ProviderId id) {
        var builtin = LlmProvider.builtin(id);
        if (builtin.isPresent()) return getProviderConfigurationError(builtin.get());
        if (!externalProviders.containsKey(id)) return "No executable LLM provider registered: " + id;
        return isProviderConfigured(id) ? null : "Provider credential is required for " + id;
    }

    public LlmProviderConfig(LocalEmbeddingService localEmbeddingService) {
        this.localEmbeddingService = localEmbeddingService;
    }

    /** Configured inference family; resolving a target never loads model weights. */
    public String getLocalModelId() {
        return localEmbeddingService.configuredModelId();
    }

    /** Structured, operator-facing validation result for {@code CUSTOM_OPENAI}. */
    public record CustomOpenAiConfigurationStatus(boolean valid, String code, String message) {
        static CustomOpenAiConfigurationStatus ready() {
            return new CustomOpenAiConfigurationStatus(true, "READY", "CUSTOM_OPENAI is configured.");
        }

        static CustomOpenAiConfigurationStatus invalid(String code, String message) {
            return new CustomOpenAiConfigurationStatus(false, code, message);
        }
    }

    public void setRequestProvider(LlmProvider provider) {
        setRequestProviderId(provider == null ? null : provider.id());
    }

    public void setRequestProviderId(ProviderId provider) {
        if (provider == null) requestProviderOverride.remove();
        else requestProviderOverride.set(requireRegisteredProvider(provider.value()));
    }

    public void clearRequestProvider() {
        requestProviderOverride.remove();
    }

    /** Bind the admitted provider identity, including MOCK, independently of this worker's defaults. */
    public RequestProviderScope withRequestProvider(String frozenProvider) {
        if (frozenProvider == null || frozenProvider.isBlank()) {
            throw new IllegalArgumentException("Frozen provider is required");
        }
        boolean mock = "MOCK".equalsIgnoreCase(frozenProvider);
        ProviderId provider = mock ? null : requireRegisteredProvider(frozenProvider);
        var scope = new RequestProviderScope();
        if (provider == null) requestProviderOverride.remove(); else requestProviderOverride.set(provider);
        requestMockOverride.set(mock);
        providerScopes.set(scope);
        return scope;
    }

    public static final class RequestProviderScope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final ProviderId previousProvider = requestProviderOverride.get();
        private final Boolean previousMock = requestMockOverride.get();
        private final RequestProviderScope previousScope = providerScopes.get();
        private boolean closed;

        private RequestProviderScope() { }

        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || providerScopes.get() != this) {
                throw new IllegalStateException("Provider scopes must close in order on their owning thread");
            }
            if (previousProvider == null) requestProviderOverride.remove(); else requestProviderOverride.set(previousProvider);
            if (previousMock == null) requestMockOverride.remove(); else requestMockOverride.set(previousMock);
            if (previousScope == null) providerScopes.remove(); else providerScopes.set(previousScope);
            closed = true;
        }
    }

    /** Compatibility accessor for built-in callers. New execution paths use the open ID. */
    public LlmProvider getActiveProvider() {
        return LlmProvider.builtin(getActiveProviderId()).orElseThrow(() ->
                new IllegalStateException("External provider requires the open provider-ID accessor"));
    }

    public ProviderId getActiveProviderId() {
        ProviderId override = requestProviderOverride.get();
        if (override != null) return requireRegisteredProvider(override.value());
        if (llmProviderConfig != null && !llmProviderConfig.isBlank()) {
            return requireRegisteredProvider(llmProviderConfig);
        }
        if (hasText(geminiApiKey)) return LlmProvider.GEMINI.id();
        if (hasText(openaiApiKey)) return LlmProvider.OPENAI.id();
        if (hasText(deepseekApiKey)) return LlmProvider.DEEPSEEK.id();
        if (hasText(qwenApiKey)) return LlmProvider.QWEN.id();
        if (hasText(llamaApiKey)) return LlmProvider.LLAMA.id();
        if (hasText(mistralApiKey)) return LlmProvider.MISTRAL.id();
        if (isCustomOpenAiConfigured()) return LlmProvider.CUSTOM_OPENAI.id();
        return LlmProvider.GEMINI.id();
    }

    public String getActiveProviderName() {
        if (isMockMode()) return "Mock";
        ProviderId id = getActiveProviderId();
        var builtin = LlmProvider.builtin(id);
        if (builtin.isEmpty()) return externalProviders.get(id).descriptor().providerName();
        return switch (builtin.get()) {
            case GEMINI -> "Gemini";
            case OPENAI -> "OpenAI";
            case DEEPSEEK -> "DeepSeek";
            case QWEN -> "Qwen";
            case LLAMA -> "Llama";
            case MISTRAL -> "Mistral";
            case CUSTOM_OPENAI -> "Custom OpenAI-compatible";
            case LOCAL_ONNX -> "Local (ONNX embeddings)";
        };
    }

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
        externalProviders.keySet().stream().filter(this::isProviderConfigured)
                .map(ProviderId::value).sorted().forEach(providers::add);
        return providers;
    }

    /**
     * Returns the transport credential. CUSTOM_OPENAI always returns either the real optional
     * bearer token or an internal no-auth marker, even when URL/model validation fails. This keeps
     * provider-agnostic callers from misdiagnosing an invalid endpoint as a missing API key.
     */
    public String getApiKey(LlmProvider provider) {
        return switch (provider) {
            case GEMINI -> geminiApiKey;
            case OPENAI -> openaiApiKey;
            case DEEPSEEK -> deepseekApiKey;
            case QWEN -> qwenApiKey;
            case LLAMA -> llamaApiKey;
            case MISTRAL -> mistralApiKey;
            case CUSTOM_OPENAI -> customOpenAiTransportApiKey();
            case LOCAL_ONNX -> null;
        };
    }

    public boolean hasConfiguredApiKey(LlmProvider provider) {
        return switch (provider) {
            case GEMINI -> hasText(geminiApiKey);
            case OPENAI -> hasText(openaiApiKey);
            case DEEPSEEK -> hasText(deepseekApiKey);
            case QWEN -> hasText(qwenApiKey);
            case LLAMA -> hasText(llamaApiKey);
            case MISTRAL -> hasText(mistralApiKey);
            case CUSTOM_OPENAI -> hasText(customLlmApiKey);
            case LOCAL_ONNX -> false;
        };
    }

    public String getOpenAiCompatibleUrl(LlmProvider provider) {
        return switch (provider) {
            case OPENAI -> OPENAI_URL;
            case DEEPSEEK -> DEEPSEEK_URL;
            case QWEN -> QWEN_URL;
            case LLAMA -> LLAMA_URL;
            case MISTRAL -> MISTRAL_URL;
            case CUSTOM_OPENAI -> trimToEmpty(customLlmUrl);
            default -> throw new IllegalArgumentException("Not an OpenAI-compatible provider: " + provider);
        };
    }

    public String getOpenAiCompatibleModel(LlmProvider provider) {
        return switch (provider) {
            case OPENAI -> OPENAI_MODEL;
            case DEEPSEEK -> DEEPSEEK_MODEL;
            case QWEN -> QWEN_MODEL;
            case LLAMA -> LLAMA_MODEL;
            case MISTRAL -> MISTRAL_MODEL;
            case CUSTOM_OPENAI -> trimToEmpty(customLlmModel);
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        };
    }

    public String getGeminiUrl() {
        return GEMINI_URL;
    }

    public CustomOpenAiConfigurationStatus getCustomOpenAiConfigurationStatus() {
        return validateCustomOpenAiConfiguration(customLlmUrl, customLlmModel);
    }

    /** Shared validation used by readiness diagnostics and the HTTP gateway. */
    static CustomOpenAiConfigurationStatus validateCustomOpenAiConfiguration(
            String configuredUrl, String configuredModel) {
        if (!hasText(configuredUrl)) {
            return CustomOpenAiConfigurationStatus.invalid("MISSING_URL",
                    "CUSTOM_LLM_URL is required when LLM_PROVIDER=CUSTOM_OPENAI.");
        }
        if (!hasText(configuredModel)) {
            return CustomOpenAiConfigurationStatus.invalid("MISSING_MODEL",
                    "CUSTOM_LLM_MODEL is required when LLM_PROVIDER=CUSTOM_OPENAI.");
        }

        URI uri;
        try {
            uri = URI.create(configuredUrl.trim());
        } catch (IllegalArgumentException exception) {
            return CustomOpenAiConfigurationStatus.invalid("INVALID_URL",
                    "CUSTOM_LLM_URL is not a valid URI.");
        }

        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return CustomOpenAiConfigurationStatus.invalid("UNSUPPORTED_SCHEME",
                    "CUSTOM_LLM_URL must use http or https.");
        }
        if (uri.getHost() == null) {
            return CustomOpenAiConfigurationStatus.invalid("MISSING_HOST",
                    "CUSTOM_LLM_URL must contain a host name.");
        }
        if (uri.getUserInfo() != null) {
            return CustomOpenAiConfigurationStatus.invalid("USER_INFO_NOT_ALLOWED",
                    "CUSTOM_LLM_URL must not contain credentials; use optional CUSTOM_LLM_API_KEY instead.");
        }
        String path = uri.getPath();
        if (path == null || !path.endsWith("/chat/completions")) {
            return CustomOpenAiConfigurationStatus.invalid("INVALID_PATH",
                    "CUSTOM_LLM_URL must end with /chat/completions.");
        }
        return CustomOpenAiConfigurationStatus.ready();
    }

    public boolean isCustomOpenAiConfigured() {
        return getCustomOpenAiConfigurationStatus().valid();
    }

    public boolean isProviderConfigured(LlmProvider provider) {
        return switch (provider) {
            case GEMINI -> hasText(geminiApiKey);
            case OPENAI -> hasText(openaiApiKey);
            case DEEPSEEK -> hasText(deepseekApiKey);
            case QWEN -> hasText(qwenApiKey);
            case LLAMA -> hasText(llamaApiKey);
            case MISTRAL -> hasText(mistralApiKey);
            case CUSTOM_OPENAI -> isCustomOpenAiConfigured();
            case LOCAL_ONNX -> localEmbeddingService.isAvailable();
        };
    }

    /** Returns a precise operator-facing reason when the selected provider is not ready. */
    public String getProviderConfigurationError(LlmProvider provider) {
        return switch (provider) {
            case GEMINI -> hasText(geminiApiKey) ? null : "GEMINI_API_KEY is required.";
            case OPENAI -> hasText(openaiApiKey) ? null : "OPENAI_API_KEY is required.";
            case DEEPSEEK -> hasText(deepseekApiKey) ? null : "DEEPSEEK_API_KEY is required.";
            case QWEN -> hasText(qwenApiKey) ? null : "DASHSCOPE_API_KEY is required.";
            case LLAMA -> hasText(llamaApiKey) ? null : "LLAMA_API_KEY is required.";
            case MISTRAL -> hasText(mistralApiKey) ? null : "MISTRAL_API_KEY is required.";
            case CUSTOM_OPENAI -> {
                CustomOpenAiConfigurationStatus status = getCustomOpenAiConfigurationStatus();
                yield status.valid() ? null : status.message();
            }
            case LOCAL_ONNX -> localEmbeddingService.isAvailable()
                    ? null
                    : "Local semantic embedding is disabled or unavailable. Set "
                    + "TAXONOMY_EMBEDDING_ENABLED=true and provide a local model or explicitly "
                    + "allow a runtime download.";
        };
    }

    public boolean hasAnyCloudApiKey() {
        return hasText(geminiApiKey)
                || hasText(openaiApiKey)
                || hasText(deepseekApiKey)
                || hasText(qwenApiKey)
                || hasText(llamaApiKey)
                || hasText(mistralApiKey)
                || hasText(customLlmApiKey);
    }

    public boolean hasAnyHttpProviderConfiguration() {
        return hasText(geminiApiKey)
                || hasText(openaiApiKey)
                || hasText(deepseekApiKey)
                || hasText(qwenApiKey)
                || hasText(llamaApiKey)
                || hasText(mistralApiKey)
                || isCustomOpenAiConfigured()
                || externalProviders.keySet().stream().anyMatch(this::isProviderConfigured);
    }

    public AiAvailabilityLevel getAvailabilityLevel() {
        if (isMockMode()) return AiAvailabilityLevel.FULL;
        ProviderId provider = getActiveProviderId();
        if (provider.equals(LlmProvider.LOCAL_ONNX.id())) {
            return localEmbeddingService.isAvailable()
                    ? AiAvailabilityLevel.LIMITED
                    : AiAvailabilityLevel.UNAVAILABLE;
        }
        if (isProviderConfigured(provider)) return AiAvailabilityLevel.FULL;
        return localEmbeddingService.isAvailable()
                ? AiAvailabilityLevel.LIMITED
                : AiAvailabilityLevel.UNAVAILABLE;
    }

    public boolean isAvailable() {
        return getAvailabilityLevel() != AiAvailabilityLevel.UNAVAILABLE;
    }

    public boolean isMockMode() {
        Boolean override = requestMockOverride.get();
        return override == null ? llmMock : override;
    }

    private String customOpenAiTransportApiKey() {
        return hasText(customLlmApiKey) ? customLlmApiKey : CUSTOM_NO_AUTH_API_KEY;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
