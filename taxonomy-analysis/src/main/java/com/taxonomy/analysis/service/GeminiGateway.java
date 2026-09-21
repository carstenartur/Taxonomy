package com.taxonomy.analysis.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;


import java.net.SocketTimeoutException;
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
    private final AnalysisRuntimeSettings preferencesService;
    private final SimpleClientHttpRequestFactory llmRequestFactory;
    private final LlmRecordReplayService recordReplayService;

    /** Sliding-window timestamps for per-gateway RPM throttling. */
    private final ArrayDeque<Long> callTimestamps = new ArrayDeque<>();

    public GeminiGateway(LlmProviderConfig providerConfig,
                         RestTemplate restTemplate,
                         ObjectMapper objectMapper,
                         LlmResponseParser responseParser,
                         AnalysisRuntimeSettings preferencesService,
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
        AnalysisRunControl.checkpoint();
        String usageInvocation = LlmTransportMeter.newInvocation();
        // REPLAY: return a previously recorded response — skips throttle and real API call.
        if (recordReplayService != null && recordReplayService.isReplayMode()) {
            Optional<String> recorded = recordReplayService.replay(prompt);
            if (recorded.isPresent()) {
                LlmTransportMeter.replay(usageInvocation, "GEMINI");
                return recorded.get();
            }
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

            int maxRetries = preferencesService != null
                    ? preferencesService.getInt("llm.retry.max", 2) : 2;
            int attempt = 0;
            while (true) {
                ResponseEntity<String> response;
                try {
                    AnalysisRunControl.phase("LLM_REQUEST", null);
                    response = LlmTransportMeter.exchange(usageInvocation, "GEMINI", attempt,
                            objectMapper, () -> restTemplate.exchange(
                                    providerConfig.getGeminiUrl() + apiKey, HttpMethod.POST, entity, String.class));
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 429) {
                        throw new LlmRateLimitException(
                                "Gemini rate limit (HTTP 429)", e);
                    }
                    throw new RuntimeException("Gemini API error " + e.getStatusCode(), e);
                } catch (HttpServerErrorException e) {
                    if (attempt < maxRetries) {
                        attempt++;
                        long backoffMs = 1000L * (1L << (attempt - 1));
                        log.warn("Gemini API server error {} — retry {}/{} after {}ms",
                                e.getStatusCode(), attempt, maxRetries, backoffMs);
                        AnalysisRunControl.pause("RETRY_WAIT", backoffMs);
                        continue;
                    }
                    throw new RuntimeException("Gemini API server error " + e.getStatusCode(), e);
                } catch (ResourceAccessException e) {
                    if (e.getCause() instanceof SocketTimeoutException) {
                        int timeoutSeconds = preferencesService != null
                                ? preferencesService.getInt("llm.timeout.seconds", 60) : 60;
                        if (attempt < maxRetries) {
                            attempt++;
                            long backoffMs = 1000L * (1L << (attempt - 1));
                            log.warn("Gemini API read timeout after {}s — retry {}/{} after {}ms",
                                    timeoutSeconds, attempt, maxRetries, backoffMs);
                            AnalysisRunControl.pause("RETRY_WAIT", backoffMs);
                            continue;
                        }
                        throw new LlmTimeoutException(
                                "Gemini API call timed out after " + timeoutSeconds + "s. "
                                + "You can increase the timeout in Preferences → llm.timeout.seconds.", e);
                    }
                    throw e;
                }

                String responseBody = response.getBody();

                if (responseBody != null && responseBody.contains("RESOURCE_EXHAUSTED")) {
                    throw new LlmRateLimitException("Gemini quota exhausted (RESOURCE_EXHAUSTED)");
                }
                if (responseBody != null && responseBody.contains("\"error\"")) {
                    log.error("Gemini API returned an error envelope ({} characters)", responseBody.length());
                    return null;
                }

                if (response.getStatusCode().is2xxSuccessful() && responseBody != null) {
                    log.info("LLM Response [GEMINI] received ({} characters)", responseBody.length());

                    // RECORD: persist prompt + response for future replay.
                    if (recordReplayService != null && recordReplayService.isRecordMode()) {
                        recordReplayService.record(prompt, responseBody, "GEMINI", null);
                    }

                    return responseBody;
                }
                log.error("Gemini API returned status {}", response.getStatusCode());
                return null;
            }
        } catch (LlmRateLimitException | LlmTimeoutException e) {
            throw e;
        } catch (LlmTransportMeter.JournalStartException unavailable) {
            throw unavailable;
        } catch (AnalysisStoppedException stopped) {
            throw stopped;
        } catch (Exception e) {
            log.error("Error calling Gemini API (exception type {})", e.getClass().getSimpleName());
            return null;
        }
    }

    // ── Per-gateway RPM throttle (sliding window) ─────────────────────────────

    /**
     * Paces outgoing calls using a sliding-window approach with the configured
     * {@code llm.rpm} preference (default {@value DEFAULT_RPM} for Gemini free tier).
     */
    void throttle() {
        if (preferencesService == null) return;
        while (true) {
            AnalysisRunControl.checkpoint();
            int rpm = preferencesService.getInt("llm.rpm", DEFAULT_RPM);
            if (rpm <= 0) return;
            long sleepMs;
            synchronized (callTimestamps) {
                long now = System.currentTimeMillis();
                long windowStart = now - 60_000L;
                while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() < windowStart) {
                    callTimestamps.pollFirst();
                }
                if (callTimestamps.size() < rpm) {
                    callTimestamps.addLast(now);
                    return;
                }
                sleepMs = Math.max(1L, callTimestamps.peekFirst() + 60_000L - now + THROTTLE_BUFFER_MS);
            }
            // Never hold the window lock while waiting: every analysis must remain cancellable.
            // Recheck capacity after waking instead of reserving cancelled calls or allowing a burst.
            AnalysisRunControl.pause("WAITING_RATE_LIMIT", sleepMs);
        }
    }

    private void applyCurrentTimeout() {
        if (preferencesService == null || llmRequestFactory == null) return;
        int timeoutSeconds = preferencesService.getInt("llm.timeout.seconds", 60);
        llmRequestFactory.setReadTimeout(timeoutSeconds * 1000);
    }
}
