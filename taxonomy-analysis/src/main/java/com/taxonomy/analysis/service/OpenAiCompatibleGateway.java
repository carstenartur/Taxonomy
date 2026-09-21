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

/** Gateway for OpenAI-compatible LLM APIs and operator-configured endpoints. */
public class OpenAiCompatibleGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleGateway.class);
    private static final long THROTTLE_BUFFER_MS = 50L;

    private final LlmProvider provider;
    private final String url;
    private final String model;
    private final int defaultRpm;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmResponseParser responseParser;
    private final AnalysisRuntimeSettings preferencesService;
    private final SimpleClientHttpRequestFactory llmRequestFactory;
    private final LlmRecordReplayService recordReplayService;

    private final ArrayDeque<Long> callTimestamps = new ArrayDeque<>();

    public OpenAiCompatibleGateway(LlmProvider provider,
                                    String url,
                                    String model,
                                    int defaultRpm,
                                    RestTemplate restTemplate,
                                    ObjectMapper objectMapper,
                                    LlmResponseParser responseParser,
                                    AnalysisRuntimeSettings preferencesService,
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
        AnalysisRunControl.checkpoint();
        String usageInvocation = LlmTransportMeter.newInvocation();
        if (recordReplayService != null && recordReplayService.isReplayMode()) {
            Optional<String> recorded = recordReplayService.replay(prompt);
            if (recorded.isPresent()) {
                LlmTransportMeter.replay(usageInvocation, provider.name());
                return recorded.get();
            }
            if (!recordReplayService.isFallbackLive()) {
                log.warn("No LLM recording found for prompt hash — no fallback configured");
                return null;
            }
            log.warn("No LLM recording found for prompt hash — falling back to live API");
        }

        validateConfiguration();
        throttle();
        applyCurrentTimeout();

        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        body.put("model", model);
        body.put("messages", List.of(message));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()
                && !LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY.equals(apiKey)) {
            headers.setBearerAuth(apiKey);
        }

        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            int maxRetries = preferencesService != null
                    ? preferencesService.getInt("llm.retry.max", 2) : 2;
            int attempt = 0;

            while (true) {
                ResponseEntity<String> response;
                try {
                    AnalysisRunControl.phase("LLM_REQUEST", null);
                    response = LlmTransportMeter.exchange(usageInvocation, provider.name(), attempt,
                            objectMapper, () -> restTemplate.exchange(url, HttpMethod.POST, entity, String.class));
                } catch (HttpClientErrorException exception) {
                    int status = exception.getStatusCode().value();
                    if (status == 429) {
                        throw new LlmRateLimitException(
                                provider + " rate limit (HTTP 429)", exception);
                    }
                    if (status == 401 || status == 403) {
                        String authenticationMessage = provider == LlmProvider.CUSTOM_OPENAI
                                ? "CUSTOM_OPENAI endpoint rejected authentication (HTTP " + status
                                + "). CUSTOM_LLM_API_KEY is optional for unauthenticated endpoints; "
                                + "set or correct it only when the endpoint requires a bearer token."
                                : provider + " endpoint rejected its configured API key (HTTP " + status + ").";
                        throw new LlmProviderException(
                                LlmProviderException.Reason.AUTHENTICATION,
                                authenticationMessage, exception);
                    }
                    throw new LlmProviderException(
                            LlmProviderException.Reason.REQUEST_REJECTED,
                            provider + " endpoint rejected the request (HTTP " + status + ")", exception);
                } catch (HttpServerErrorException exception) {
                    if (attempt < maxRetries) {
                        attempt++;
                        long backoffMs = 1000L * (1L << (attempt - 1));
                        log.warn("{} API server error {} — retry {}/{} after {}ms",
                                provider, exception.getStatusCode(), attempt, maxRetries, backoffMs);
                        AnalysisRunControl.pause("RETRY_WAIT", backoffMs);
                        continue;
                    }
                    throw new LlmProviderException(
                            LlmProviderException.Reason.REQUEST_REJECTED,
                            provider + " endpoint returned a server error "
                                    + exception.getStatusCode(), exception);
                } catch (ResourceAccessException exception) {
                    if (exception.getCause() instanceof SocketTimeoutException) {
                        int timeoutSeconds = preferencesService != null
                                ? preferencesService.getInt("llm.timeout.seconds", 60) : 60;
                        if (attempt < maxRetries) {
                            attempt++;
                            long backoffMs = 1000L * (1L << (attempt - 1));
                            log.warn("{} API read timeout after {}s — retry {}/{} after {}ms",
                                    provider, timeoutSeconds, attempt, maxRetries, backoffMs);
                            AnalysisRunControl.pause("RETRY_WAIT", backoffMs);
                            continue;
                        }
                        throw new LlmTimeoutException(
                                provider + " API call timed out after " + timeoutSeconds + "s. "
                                        + "You can increase the timeout in Preferences → llm.timeout.seconds.",
                                exception);
                    }
                    String endpointMessage = provider == LlmProvider.CUSTOM_OPENAI
                            ? "CUSTOM_OPENAI endpoint is unreachable. Check CUSTOM_LLM_URL, service "
                            + "availability, DNS and network policy."
                            : provider + " endpoint is unreachable.";
                    throw new LlmProviderException(
                            LlmProviderException.Reason.ENDPOINT_UNREACHABLE,
                            endpointMessage, exception);
                }

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    log.info("LLM Response [{}] received ({} characters)", provider, response.getBody().length());
                    if (recordReplayService != null && recordReplayService.isRecordMode()) {
                        recordReplayService.record(prompt, response.getBody(), provider.name(), null);
                    }
                    return response.getBody();
                }
                log.error("{} API returned status {}", provider, response.getStatusCode());
                return null;
            }
        } catch (LlmRateLimitException | LlmTimeoutException | LlmProviderException exception) {
            throw exception;
        } catch (LlmTransportMeter.JournalStartException unavailable) {
            throw unavailable;
        } catch (AnalysisStoppedException stopped) {
            throw stopped;
        } catch (Exception exception) {
            log.error("Error calling {} API (exception type {})", provider, exception.getClass().getSimpleName());
            return null;
        }
    }

    private void validateConfiguration() {
        if (provider != LlmProvider.CUSTOM_OPENAI) return;
        LlmProviderConfig.CustomOpenAiConfigurationStatus status =
                LlmProviderConfig.validateCustomOpenAiConfiguration(url, model);
        if (!status.valid()) {
            throw new LlmProviderException(
                    LlmProviderException.Reason.CONFIGURATION, status.message());
        }
    }

    void throttle() {
        if (preferencesService == null) return;
        while (true) {
            AnalysisRunControl.checkpoint();
            int rpm = preferencesService.getInt("llm.rpm." + provider.name().toLowerCase(Locale.ROOT), defaultRpm);
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
