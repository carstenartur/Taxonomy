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
                               @Autowired(required = false) LlmRecordReplayService recordReplayService) {

        LlmResponseParser responseParser = new LlmResponseParser(objectMapper);

        gateways = new EnumMap<>(LlmProvider.class);

        // Gemini: default 5 RPM (free tier)
        gateways.put(LlmProvider.GEMINI, new GeminiGateway(
                providerConfig, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService));

        // OpenAI: default 60 RPM (paid)
        gateways.put(LlmProvider.OPENAI, new OpenAiCompatibleGateway(
                LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL, LlmProviderConfig.OPENAI_MODEL,
                60, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService));

        // DeepSeek: default 0 RPM (no throttle — generous limits)
        gateways.put(LlmProvider.DEEPSEEK, new OpenAiCompatibleGateway(
                LlmProvider.DEEPSEEK, LlmProviderConfig.DEEPSEEK_URL, LlmProviderConfig.DEEPSEEK_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService));

        // Qwen: default 0 RPM (no throttle)
        gateways.put(LlmProvider.QWEN, new OpenAiCompatibleGateway(
                LlmProvider.QWEN, LlmProviderConfig.QWEN_URL, LlmProviderConfig.QWEN_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService));

        // Llama: default 0 RPM (no throttle — self-hosted or generous API)
        gateways.put(LlmProvider.LLAMA, new OpenAiCompatibleGateway(
                LlmProvider.LLAMA, LlmProviderConfig.LLAMA_URL, LlmProviderConfig.LLAMA_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService));

        // Mistral: default 0 RPM (no throttle)
        gateways.put(LlmProvider.MISTRAL, new OpenAiCompatibleGateway(
                LlmProvider.MISTRAL, LlmProviderConfig.MISTRAL_URL, LlmProviderConfig.MISTRAL_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService));

        log.info("LlmGatewayRegistry initialised with {} gateways", gateways.size());
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
        LlmGateway gw = gateways.get(provider);
        if (gw == null) {
            throw new IllegalArgumentException(
                    "No HTTP gateway registered for provider " + provider
                    + ". LOCAL_ONNX uses local embeddings, not an HTTP gateway.");
        }
        return gw;
    }
}
