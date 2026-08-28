package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.taxonomy.preferences.PreferencesService;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry that creates and holds one {@link LlmGateway} per {@link LlmProvider}.
 *
 * <p>Each gateway instance has its own independent throttle queue, so providers
 * with generous rate limits (e.g. paid OpenAI at 60 RPM) are not penalised by
 * providers with strict limits (e.g. Gemini free tier at 5 RPM).
 *
 * <p>Every production HTTP gateway is wrapped by a final-prompt budget boundary.
 * The wrapper evaluates the complete prompt after templates and taxonomy context
 * have been assembled, immediately before the provider call.
 *
 * <p>Gateways for {@link LlmProvider#LOCAL_ONNX} are not provided here because
 * local embeddings do not use HTTP calls. {@code LlmService} handles that case directly.
 */
@Component
public class LlmGatewayRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayRegistry.class);

    private final Map<LlmProvider, LlmGateway> gateways;

    @Autowired
    public LlmGatewayRegistry(LlmProviderConfig providerConfig,
                               RestTemplate restTemplate,
                               ObjectMapper objectMapper,
                               @Autowired(required = false) @Lazy PreferencesService preferencesService,
                               @Autowired(required = false) SimpleClientHttpRequestFactory llmRequestFactory,
                               @Autowired(required = false) LlmRecordReplayService recordReplayService,
                               AiPromptBudgetPolicy promptBudgetPolicy) {

        LlmResponseParser responseParser = new LlmResponseParser(objectMapper);

        gateways = new EnumMap<>(LlmProvider.class);

        // Gemini: default 5 RPM (free tier)
        register(LlmProvider.GEMINI, new GeminiGateway(
                providerConfig, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);

        // OpenAI: default 60 RPM (paid)
        register(LlmProvider.OPENAI, new OpenAiCompatibleGateway(
                LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL, LlmProviderConfig.OPENAI_MODEL,
                60, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);

        // DeepSeek: default 0 RPM (no throttle — generous limits)
        register(LlmProvider.DEEPSEEK, new OpenAiCompatibleGateway(
                LlmProvider.DEEPSEEK, LlmProviderConfig.DEEPSEEK_URL, LlmProviderConfig.DEEPSEEK_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);

        // Qwen: default 0 RPM (no throttle)
        register(LlmProvider.QWEN, new OpenAiCompatibleGateway(
                LlmProvider.QWEN, LlmProviderConfig.QWEN_URL, LlmProviderConfig.QWEN_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);

        // Llama: default 0 RPM (no throttle — self-hosted or generous API)
        register(LlmProvider.LLAMA, new OpenAiCompatibleGateway(
                LlmProvider.LLAMA, LlmProviderConfig.LLAMA_URL, LlmProviderConfig.LLAMA_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);

        // Mistral: default 0 RPM (no throttle)
        register(LlmProvider.MISTRAL, new OpenAiCompatibleGateway(
                LlmProvider.MISTRAL, LlmProviderConfig.MISTRAL_URL, LlmProviderConfig.MISTRAL_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);

        // Custom OpenAI-compatible endpoint: default 0 RPM (operator-controlled server)
        register(LlmProvider.CUSTOM_OPENAI, new OpenAiCompatibleGateway(
                LlmProvider.CUSTOM_OPENAI,
                providerConfig.getOpenAiCompatibleUrl(LlmProvider.CUSTOM_OPENAI),
                providerConfig.getOpenAiCompatibleModel(LlmProvider.CUSTOM_OPENAI),
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);

        log.info("LlmGatewayRegistry initialised with {} gateways", gateways.size());
    }

    /** Test-only compatibility constructor preserving direct gateway type assertions. */
    LlmGatewayRegistry(LlmProviderConfig providerConfig,
                       RestTemplate restTemplate,
                       ObjectMapper objectMapper,
                       PreferencesService preferencesService,
                       SimpleClientHttpRequestFactory llmRequestFactory,
                       LlmRecordReplayService recordReplayService) {
        this(providerConfig, restTemplate, objectMapper, preferencesService,
                llmRequestFactory, recordReplayService, null);
    }

    private void register(
            LlmProvider provider,
            LlmGateway gateway,
            AiPromptBudgetPolicy promptBudgetPolicy) {
        gateways.put(provider, promptBudgetPolicy == null
                ? gateway
                : new PromptBudgetEnforcingLlmGateway(gateway, promptBudgetPolicy));
    }

    /**
     * Returns the gateway for the given provider.
     *
     * @param provider the LLM provider
     * @return the corresponding gateway
     * @throws IllegalArgumentException if no gateway exists for the provider
     *         (e.g. {@link LlmProvider#LOCAL_ONNX})
     */
    public LlmGateway getGateway(LlmProvider provider) {
        LlmGateway gateway = gateways.get(provider);
        if (gateway == null) {
            throw new IllegalArgumentException(
                    "No HTTP gateway registered for provider " + provider
                    + ". LOCAL_ONNX uses local embeddings, not an HTTP gateway.");
        }
        return gateway;
    }
}
