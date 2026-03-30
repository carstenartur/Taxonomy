package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.taxonomy.preferences.PreferencesService;

import java.util.*;

/**
 * Gateway for OpenAI-compatible LLM APIs (OpenAI, DeepSeek, Qwen, Llama, Mistral).
 *
 * <p>All these providers share the same request/response format (messages array with
 * role/content, Bearer auth), but may have different endpoints, model names, and
 * rate limits.
 *
 * <p>Each {@code OpenAiCompatibleGateway} instance maintains its own sliding-window
 * throttle queue, so providers with generous rate limits (e.g. paid OpenAI) are not
 * penalised by providers with strict limits.
 *
 * <p>When {@code defaultRpm} is 0, no throttling is applied (suitable for self-hosted
 * models like Llama or Mistral with no API rate limit).
 */
public class OpenAiCompatibleGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleGateway.class);

    /** Buffer added to the sleep duration in the RPM throttle (ms). */
    private static final long THROTTLE_BUFFER_MS = 50L;

    private final LlmProvider provider;
    private final String url;
    private final String model;
    private final int defaultRpm;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmResponseParser responseParser;
    private final PreferencesService preferencesService;
    private final SimpleClientHttpRequestFactory llmRequestFactory;
    private final LlmRecordReplayService recordReplayService;

    /** Sliding-window timestamps for per-gateway RPM throttling. */
    private final ArrayDeque<Long> callTimestamps = new ArrayDeque<>();

    public OpenAiCompatibleGateway(LlmProvider provider,
                                    String url,
                                    String model,
                                    int defaultRpm,
                                    RestTemplate restTemplate,
                                    ObjectMapper objectMapper,
                                    LlmResponseParser responseParser,
                                    PreferencesService preferencesService,
                                    SimpleClientHttpRequestFactory llmRequestFactory,
                                    LlmRecordReplayService recordReplayService) {
        this.provider = provider;
        this.url = url;
        this.model = model;
        this.defaultRpm = defaultRpm;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.responseParser = responseParser;
        this.preferencesService = preferencesService;
        this.llmRequestFactory = llmRequestFactory;
        this.recordReplayService = recordReplayService;
    }

    @Override
    public String providerName() {
        return provider.name();
    }

    @Override
    public String extractResponseText(String rawResponseBody) {
        return responseParser.extractOpenAiText(rawResponseBody);
    }

    @Override
    public String sendHttpRequest(String prompt, String apiKey) {
        // REPLAY: return a previously recorded response — skips throttle and real API call.
        if (recordReplayService != null && recordReplayService.isReplayMode()) {
            Optional<String> recorded = recordReplayService.replay(prompt);
            if (recorded.isPresent()) return recorded.get();
            if (!recordReplayService.isFallbackLive()) {
                log.warn("No LLM recording found for prompt hash — no fallback configured");
                return null;
            }
            log.warn("No LLM recording found for prompt hash — falling back to live API");
        }

        // Real API path — throttle to respect RPM rate limits
        throttle();
        applyCurrentTimeout();

        Map<String, Object> body    = new LinkedHashMap<>();
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        body.put("model", model);
        body.put("messages", List.of(message));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    throw new LlmRateLimitException(
                            provider + " rate limit (HTTP 429): " + e.getResponseBodyAsString(), e);
                }
                throw new RuntimeException(provider + " API error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException e) {
                throw new RuntimeException(provider + " API server error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            }

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("LLM Response [{}] — raw response (first 500 chars): {}",
                        provider, response.getBody().substring(0, Math.min(response.getBody().length(), 500)));

                // RECORD: persist prompt + response for future replay.
                if (recordReplayService != null && recordReplayService.isRecordMode()) {
                    recordReplayService.record(prompt, response.getBody(), provider.name(), null);
                }

                return response.getBody();
            }
            log.error("{} API returned status {}", provider, response.getStatusCode());
            return null;
        } catch (LlmRateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling {} API", provider, e);
            return null;
        }
    }

    // ── Per-gateway RPM throttle (sliding window) ─────────────────────────────

    /**
     * Paces outgoing calls using a sliding-window approach.
     *
     * <p>Reads the provider-specific preference {@code llm.rpm.<provider>} first,
     * then falls back to the constructor-provided {@code defaultRpm}.
     * When the effective RPM is 0, no throttling is applied.
     */
    synchronized void throttle() {
        if (preferencesService == null) return;
        String prefKey = "llm.rpm." + provider.name().toLowerCase();
        int rpm = preferencesService.getInt(prefKey, defaultRpm);
        if (rpm <= 0) return;

        long now = System.currentTimeMillis();
        long windowStart = now - 60_000L;

        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() < windowStart) {
            callTimestamps.pollFirst();
        }

        if (callTimestamps.size() >= rpm) {
            long oldest = callTimestamps.peekFirst();
            long sleepMs = oldest + 60_000L - System.currentTimeMillis() + THROTTLE_BUFFER_MS;
            if (sleepMs > 0) {
                log.debug("{} RPM throttle: sleeping {}ms (rpm={}, calls in window={})",
                        provider, sleepMs, rpm, callTimestamps.size());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        callTimestamps.addLast(System.currentTimeMillis());
    }

    private void applyCurrentTimeout() {
        if (preferencesService == null || llmRequestFactory == null) return;
        int timeoutSeconds = preferencesService.getInt("llm.timeout.seconds", 30);
        llmRequestFactory.setReadTimeout(timeoutSeconds * 1000);
    }
}
