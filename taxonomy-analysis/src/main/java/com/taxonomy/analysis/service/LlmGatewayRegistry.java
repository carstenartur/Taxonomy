package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry with one independent HTTP admission policy per configured provider.
 * Production gateways retain the final-prompt budget boundary. Local embeddings
 * do not use HTTP gateways and are handled by {@link LlmService} directly.
 */
@Component
public class LlmGatewayRegistry {
    private static final Logger log = LoggerFactory.getLogger(LlmGatewayRegistry.class);
    private final Map<LlmProvider, LlmGateway> gateways;
    private final List<LlmGateway> transports = new ArrayList<>();

    @Autowired
    public LlmGatewayRegistry(LlmProviderConfig providerConfig,
                               RestTemplate restTemplate,
                               ObjectMapper objectMapper,
                               @Autowired(required = false) @Lazy AnalysisRuntimeSettings preferencesService,
                               @Autowired(required = false) SimpleClientHttpRequestFactory llmRequestFactory,
                               @Autowired(required = false) LlmRecordReplayService recordReplayService,
                               AiPromptBudgetPolicy promptBudgetPolicy) {
        LlmResponseParser responseParser = new LlmResponseParser(objectMapper);
        gateways = new EnumMap<>(LlmProvider.class);
        register(LlmProvider.GEMINI, new GeminiGateway(
                providerConfig, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);
        register(LlmProvider.OPENAI, new OpenAiCompatibleGateway(
                LlmProvider.OPENAI, LlmProviderConfig.OPENAI_URL, LlmProviderConfig.OPENAI_MODEL,
                60, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);
        register(LlmProvider.DEEPSEEK, new OpenAiCompatibleGateway(
                LlmProvider.DEEPSEEK, LlmProviderConfig.DEEPSEEK_URL, LlmProviderConfig.DEEPSEEK_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);
        register(LlmProvider.QWEN, new OpenAiCompatibleGateway(
                LlmProvider.QWEN, LlmProviderConfig.QWEN_URL, LlmProviderConfig.QWEN_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);
        register(LlmProvider.LLAMA, new OpenAiCompatibleGateway(
                LlmProvider.LLAMA, LlmProviderConfig.LLAMA_URL, LlmProviderConfig.LLAMA_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);
        register(LlmProvider.MISTRAL, new OpenAiCompatibleGateway(
                LlmProvider.MISTRAL, LlmProviderConfig.MISTRAL_URL, LlmProviderConfig.MISTRAL_MODEL,
                0, restTemplate, objectMapper, responseParser,
                preferencesService, llmRequestFactory, recordReplayService), promptBudgetPolicy);
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
                       AnalysisRuntimeSettings preferencesService,
                       SimpleClientHttpRequestFactory llmRequestFactory,
                       LlmRecordReplayService recordReplayService) {
        this(providerConfig, restTemplate, objectMapper, preferencesService,
                llmRequestFactory, recordReplayService, null);
    }

    /** Configure before publication; do not replace limiters while requests are executing. */
    @Autowired
    void configureRequestAdmission(Environment environment) {
        for (LlmGateway gateway : transports) {
            String prefix = "taxonomy.llm.providers." + gateway.providerName().toLowerCase(Locale.ROOT) + ".";
            int concurrent = setting(environment, prefix, "max-concurrent-requests", 4);
            int queued = setting(environment, prefix, "request-queue-capacity", 64);
            int seconds = setting(environment, prefix, "maximum-queue-wait-seconds", 120);
            var limits = new ProviderRequestLimiter.Limits(concurrent, queued, seconds * 1000L);
            if (gateway instanceof OpenAiCompatibleGateway openAi) openAi.configureRequestLimits(limits);
            else if (gateway instanceof GeminiGateway gemini) gemini.configureRequestLimits(limits);
        }
    }

    private static int setting(Environment environment, String providerPrefix, String key, int fallback) {
        return environment.getProperty(providerPrefix + key, Integer.class,
                environment.getProperty("taxonomy.llm." + key, Integer.class, fallback));
    }

    private void register(LlmProvider provider, LlmGateway gateway, AiPromptBudgetPolicy promptBudgetPolicy) {
        transports.add(gateway);
        gateways.put(provider, promptBudgetPolicy == null
                ? gateway : new PromptBudgetEnforcingLlmGateway(gateway, promptBudgetPolicy));
    }

    public LlmGateway getGateway(LlmProvider provider) {
        LlmGateway gateway = gateways.get(provider);
        if (gateway == null) {
            throw new IllegalArgumentException("No HTTP gateway registered for provider " + provider
                    + ". LOCAL_ONNX uses local embeddings, not an HTTP gateway.");
        }
        return gateway;
    }
}
