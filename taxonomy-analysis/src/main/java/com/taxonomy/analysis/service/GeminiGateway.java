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
 * Gateway for the Google Gemini LLM API. Each physical HTTP attempt uses the
 * provider's shared concurrency/RPM admission; other providers remain independent.
 */
public class GeminiGateway implements LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(GeminiGateway.class);
    static final int DEFAULT_RPM = 5;

    private final LlmProviderConfig providerConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmResponseParser responseParser;
    private final AnalysisRuntimeSettings preferencesService;
    private final SimpleClientHttpRequestFactory llmRequestFactory;
    private final LlmRecordReplayService recordReplayService;
    private final LlmRequestAdmission requestAdmission;

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
        this.requestAdmission = new LlmRequestAdmission(LlmProvider.GEMINI, DEFAULT_RPM, preferencesService);
    }

    void configureRequestLimits(ProviderRequestLimiter.Limits limits) { requestAdmission.configure(limits); }
    void configureProviderPermits(ProviderConcurrencyPermits permits) { requestAdmission.configure(permits); }

    @Override public String providerName() { return "GEMINI"; }

    @Override public String extractResponseText(String rawResponseBody) {
        return responseParser.extractGeminiText(rawResponseBody);
    }

    @Override
    public String sendHttpRequest(String prompt, String apiKey) {
        AnalysisRunControl.checkpoint();
        String usageInvocation = LlmTransportMeter.newInvocation();
        // REPLAY never reserves network capacity or consumes a provider request budget.
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

        applyCurrentTimeout();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("text", prompt);
        content.put("parts", List.of(part));
        body.put("contents", List.of(content));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            int maxRetries = Math.max(0, Math.min(6, preferencesService != null
                    ? preferencesService.getInt("llm.retry.max", 2) : 2));
            int attempt = 0;
            while (true) {
                ResponseEntity<String> response;
                try {
                    response = sendAttempt(usageInvocation, attempt, entity, apiKey);
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 429) {
                        if (requestAdmission.retryRateLimit(e, objectMapper, attempt, maxRetries)) {
                            attempt++;
                            continue;
                        }
                        throw new LlmRateLimitException("Gemini rate limit (HTTP 429)", e);
                    }
                    throw new RuntimeException("Gemini API error " + e.getStatusCode(), e);
                } catch (HttpServerErrorException e) {
                    if (attempt < maxRetries) {
                        long backoffMs = ProviderRetryPolicy.backoffMillis(attempt++);
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
                            long backoffMs = ProviderRetryPolicy.backoffMillis(attempt++);
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

    private ResponseEntity<String> sendAttempt(String invocation, int attempt, HttpEntity<String> entity, String apiKey) {
        return requestAdmission.execute(() -> {
            AnalysisRunControl.phase("LLM_REQUEST", null);
            return LlmTransportMeter.exchange(invocation, "GEMINI", attempt, objectMapper,
                    () -> restTemplate.exchange(providerConfig.getGeminiUrl() + apiKey,
                            HttpMethod.POST, entity, String.class));
        });
    }

    /** Kept for direct throttle contracts; production admission surrounds every HTTP attempt. */
    void throttle() { requestAdmission.execute(() -> null); }

    private void applyCurrentTimeout() {
        if (preferencesService == null || llmRequestFactory == null) return;
        int timeoutSeconds = preferencesService.getInt("llm.timeout.seconds", 60);
        llmRequestFactory.setReadTimeout(timeoutSeconds * 1000);
    }
}
