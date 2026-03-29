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
 * Gateway for the Google Gemini LLM API.
 *
 * <p>Handles Gemini-specific request formatting (contents/parts structure),
 * error detection ({@code RESOURCE_EXHAUSTED} in response body), and
 * per-gateway RPM throttling (default 5 RPM for the Gemini free tier).
 *
 * <p>Each {@code GeminiGateway} instance maintains its own sliding-window
 * throttle queue, so Gemini rate limits do not affect other providers.
 */
public class GeminiGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(GeminiGateway.class);

    /** Buffer added to the sleep duration in the RPM throttle (ms). */
    private static final long THROTTLE_BUFFER_MS = 50L;

    /** Default RPM for Gemini free tier. */
    static final int DEFAULT_RPM = 5;

    private final LlmProviderConfig providerConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmResponseParser responseParser;
    private final PreferencesService preferencesService;
    private final SimpleClientHttpRequestFactory llmRequestFactory;
    private final LlmRecordReplayService recordReplayService;

    /** Sliding-window timestamps for per-gateway RPM throttling. */
    private final ArrayDeque<Long> callTimestamps = new ArrayDeque<>();

    public GeminiGateway(LlmProviderConfig providerConfig,
                         RestTemplate restTemplate,
                         ObjectMapper objectMapper,
                         LlmResponseParser responseParser,
                         PreferencesService preferencesService,
                         SimpleClientHttpRequestFactory llmRequestFactory,
                         LlmRecordReplayService recordReplayService) {
        this.providerConfig = providerConfig;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.responseParser = responseParser;
        this.preferencesService = preferencesService;
        this.llmRequestFactory = llmRequestFactory;
        this.recordReplayService = recordReplayService;
    }

    @Override
    public String providerName() {
        return "GEMINI";
    }

    @Override
    public String extractResponseText(String rawResponseBody) {
        return responseParser.extractGeminiText(rawResponseBody);
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
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, Object> part    = new LinkedHashMap<>();
        part.put("text", prompt);
        content.put("parts", List.of(part));
        body.put("contents", List.of(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> response;
            try {
                response = restTemplate.exchange(
                        providerConfig.getGeminiUrl() + apiKey, HttpMethod.POST, entity, String.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    throw new LlmRateLimitException(
                            "Gemini rate limit (HTTP 429): " + e.getResponseBodyAsString(), e);
                }
                throw new RuntimeException("Gemini API error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            } catch (HttpServerErrorException e) {
                throw new RuntimeException("Gemini API server error " + e.getStatusCode() + ": " +
                        e.getResponseBodyAsString(), e);
            }

            String responseBody = response.getBody();

            if (responseBody != null && responseBody.contains("RESOURCE_EXHAUSTED")) {
                throw new LlmRateLimitException("Gemini quota exhausted: " + responseBody);
            }
            if (responseBody != null && responseBody.contains("\"error\"")) {
                log.error("Gemini API returned error in body: {}", responseBody);
                return null;
            }

            if (response.getStatusCode().is2xxSuccessful() && responseBody != null) {
                log.info("LLM Response [GEMINI] — raw response (first 500 chars): {}",
                        responseBody.substring(0, Math.min(responseBody.length(), 500)));

                // RECORD: persist prompt + response for future replay.
                if (recordReplayService != null && recordReplayService.isRecordMode()) {
                    recordReplayService.record(prompt, responseBody, "GEMINI", null);
                }

                return responseBody;
            }
            log.error("Gemini API returned status {}", response.getStatusCode());
            return null;
        } catch (LlmRateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling Gemini API", e);
            return null;
        }
    }

    // ── Per-gateway RPM throttle (sliding window) ─────────────────────────────

    /**
     * Paces outgoing calls using a sliding-window approach with the configured
     * {@code llm.rpm} preference (default {@value DEFAULT_RPM} for Gemini free tier).
     */
    synchronized void throttle() {
        if (preferencesService == null) return;
        int rpm = preferencesService.getInt("llm.rpm", DEFAULT_RPM);
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
                log.debug("Gemini RPM throttle: sleeping {}ms (rpm={}, calls in window={})",
                        sleepMs, rpm, callTimestamps.size());
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
