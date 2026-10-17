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

    private final LlmProvider provider;
    private final String url;
    private final String model;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final LlmResponseParser responseParser;
    private final AnalysisRuntimeSettings preferencesService;
    private final SimpleClientHttpRequestFactory llmRequestFactory;
    private final LlmRecordReplayService recordReplayService;
    private final LlmRequestAdmission requestAdmission;
    private final OpenAiResponsesHttpTransport responsesTransport;
    private boolean structuredOutputEnabled = true;
    private int maximumOutputTokens = 4096;
    private LlmProviderConfig.CustomOpenAiConfigurationStatus configuredCustomStatus;

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
        this(provider, url, model, defaultRpm, restTemplate, objectMapper, responseParser, preferencesService,
                llmRequestFactory, recordReplayService, new OpenAiResponsesHttpTransport());
    }

    OpenAiCompatibleGateway(LlmProvider provider, String url, String model, int defaultRpm,
                            RestTemplate restTemplate, ObjectMapper objectMapper, LlmResponseParser responseParser,
                            AnalysisRuntimeSettings preferencesService, SimpleClientHttpRequestFactory llmRequestFactory,
                            LlmRecordReplayService recordReplayService, OpenAiResponsesHttpTransport responsesTransport) {
        this.provider = provider;
        this.url = url;
        this.model = model;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.responseParser = responseParser;
        this.preferencesService = preferencesService;
        this.llmRequestFactory = llmRequestFactory;
        this.recordReplayService = recordReplayService;
        this.requestAdmission = new LlmRequestAdmission(provider, defaultRpm, preferencesService);
        this.responsesTransport = Objects.requireNonNull(responsesTransport);
    }

    void configureRequestLimits(ProviderRequestLimiter.Limits limits) { requestAdmission.configure(limits); }
    void configureProviderPermits(ProviderConcurrencyPermits permits) { requestAdmission.configure(permits); }
    void configureProviderQuotas(ProviderRateQuotas quotas) { requestAdmission.configure(quotas); }
    void configureCompletionLimits(boolean structuredOutputEnabled, int maximumOutputTokens) {
        this.structuredOutputEnabled = structuredOutputEnabled;
        this.maximumOutputTokens = maximumOutputTokens;
    }
    void configureCustomStatus(LlmProviderConfig.CustomOpenAiConfigurationStatus status) {
        this.configuredCustomStatus = status;
    }

    @Override public String providerName() { return provider.name(); }

    @Override public String extractResponseText(String rawResponseBody) {
        boolean responses = false;
        try { var root = rawResponseBody == null ? null : objectMapper.readTree(rawResponseBody);
            responses = root != null && "response".equals(root.path("object").asText()); }
        catch (RuntimeException invalid) { /* Preserve the old parser's malformed-response behavior. */ }
        if (responses) return OpenAiResponsesTokenContract.extractText(rawResponseBody, objectMapper);
        return responseParser.extractOpenAiText(rawResponseBody);
    }

    @Override
    public String sendHttpRequest(String prompt, String apiKey) {
        return sendHttpRequest(prompt, apiKey, null);
    }

    @Override
    public String sendHttpRequest(String prompt, String apiKey, LlmCompletionOptions options) {
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
        applyCurrentTimeout();

        var tokenBudget = requestAdmission.requiredTokenBudget();
        if (tokenBudget != null) {
            return sendResponses(prompt, apiKey, usageInvocation, tokenBudget, options);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        body.put("model", model);
        body.put("messages", List.of(message));
        if (options != null) {
            body.put("max_tokens", provider == LlmProvider.CUSTOM_OPENAI
                    ? Math.min(options.maxOutputTokens(), maximumOutputTokens) : options.maxOutputTokens());
            body.put("temperature", 0);
            if (options.schema() != null && (provider == LlmProvider.OPENAI
                    || provider == LlmProvider.CUSTOM_OPENAI && structuredOutputEnabled)) {
                body.put("response_format", Map.of("type", "json_schema", "json_schema", Map.of(
                        "name", options.schemaName(), "strict", true, "schema", options.schema())));
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()
                && !LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY.equals(apiKey)) {
            headers.setBearerAuth(apiKey);
        }

        try {
            HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
            int maxRetries = Math.max(0, Math.min(6, preferencesService != null
                    ? preferencesService.getInt("llm.retry.max", 2) : 2));
            int attempt = 0;

            while (true) {
                ResponseEntity<String> response;
                try {
                    response = sendAttempt(usageInvocation, attempt, entity);
                } catch (HttpClientErrorException exception) {
                    int status = exception.getStatusCode().value();
                    if (status == 429) {
                        if (requestAdmission.retryRateLimit(exception, objectMapper, attempt, maxRetries)) {
                            attempt++;
                            continue;
                        }
                        throw new LlmRateLimitException(provider + " rate limit (HTTP 429)", exception);
                    }
                    if (status == 401 || status == 403) {
                        String authenticationMessage = provider == LlmProvider.CUSTOM_OPENAI
                                ? "CUSTOM_OPENAI endpoint rejected authentication (HTTP " + status
                                + "). CUSTOM_LLM_API_KEY is optional for unauthenticated endpoints; "
                                + "set or correct it only when the endpoint requires a bearer token."
                                : provider + " endpoint rejected its configured API key (HTTP " + status + ").";
                        throw new LlmProviderException(
                                LlmProviderException.Reason.AUTHENTICATION, authenticationMessage, exception);
                    }
                    throw new LlmProviderException(
                            LlmProviderException.Reason.REQUEST_REJECTED,
                            provider + " endpoint rejected the request (HTTP " + status + ")", exception);
                } catch (HttpServerErrorException exception) {
                    if (attempt < maxRetries) {
                        long backoffMs = ProviderRetryPolicy.backoffMillis(attempt++);
                        log.warn("{} API server error {} — retry {}/{} after {}ms",
                                provider, exception.getStatusCode(), attempt, maxRetries, backoffMs);
                        AnalysisRunControl.pause("RETRY_WAIT", backoffMs);
                        continue;
                    }
                    throw new LlmProviderException(
                            LlmProviderException.Reason.REQUEST_REJECTED,
                            provider + " endpoint returned a server error " + exception.getStatusCode(), exception);
                } catch (ResourceAccessException exception) {
                    if (exception.getCause() instanceof SocketTimeoutException) {
                        int timeoutSeconds = preferencesService != null
                                ? preferencesService.getInt("llm.timeout.seconds", 60) : 60;
                        if (attempt < maxRetries) {
                            long backoffMs = ProviderRetryPolicy.backoffMillis(attempt++);
                            log.warn("{} API read timeout after {}s — retry {}/{} after {}ms",
                                    provider, timeoutSeconds, attempt, maxRetries, backoffMs);
                            AnalysisRunControl.pause("RETRY_WAIT", backoffMs);
                            continue;
                        }
                        throw new LlmTimeoutException(
                                provider + " API call timed out after " + timeoutSeconds + "s. "
                                        + "You can increase the timeout in Preferences → llm.timeout.seconds.", exception);
                    }
                    String endpointMessage = provider == LlmProvider.CUSTOM_OPENAI
                            ? "CUSTOM_OPENAI endpoint is unreachable. Check CUSTOM_LLM_URL, service "
                            + "availability, DNS and network policy."
                            : provider + " endpoint is unreachable.";
                    throw new LlmProviderException(
                            LlmProviderException.Reason.ENDPOINT_UNREACHABLE, endpointMessage, exception);
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
        } catch (LlmTransportMeter.JournalStartException | ProviderRateQuotas.UnavailableException unavailable) {
            throw unavailable;
        } catch (AnalysisStoppedException stopped) {
            throw stopped;
        } catch (Exception exception) {
            log.error("Error calling {} API (exception type {})", provider, exception.getClass().getSimpleName());
            return null;
        }
    }

    private ResponseEntity<String> sendAttempt(String invocation, int attempt, HttpEntity<String> entity) {
        return requestAdmission.execute(() -> {
            AnalysisRunControl.phase("LLM_REQUEST", null);
            return LlmTransportMeter.exchange(invocation, provider.name(), attempt, objectMapper,
                    () -> restTemplate.exchange(url, HttpMethod.POST, entity, String.class));
        });
    }

    private String sendResponses(String prompt, String apiKey, String invocation, ProviderRateQuotas.TokenBudget budget,
            LlmCompletionOptions options) {
        var prepared = OpenAiResponsesTokenContract.prepare(provider, url, model, budget, prompt, objectMapper, options);
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank() && !LlmProviderConfig.CUSTOM_NO_AUTH_API_KEY.equals(apiKey)) headers.setBearerAuth(apiKey);
        var counted = sendResponsesEndpoint(invocation, budget, headers, prepared.countBody(), true);
        OpenAiResponsesTokenContract.validateCount(counted.getBody(), budget, objectMapper);
        AnalysisRunControl.checkpoint();
        var response = sendResponsesEndpoint(invocation, budget, headers, prepared.generationBody(), false);
        OpenAiResponsesTokenContract.extractText(response.getBody(), objectMapper);
        AnalysisRunControl.checkpoint();
        if (recordReplayService != null && recordReplayService.isRecordMode()) {
            recordReplayService.record(prompt, response.getBody(), provider.name(), null);
        }
        return response.getBody();
    }

    private ResponseEntity<String> sendResponsesEndpoint(String invocation, ProviderRateQuotas.TokenBudget budget,
            HttpHeaders headers, String body, boolean count) {
        int maxRetries = Math.max(0, Math.min(6, preferencesService != null ? preferencesService.getInt("llm.retry.max", 2) : 2));
        long timeout = Math.min(OpenAiResponsesTokenContract.MAX_OPERATION_MILLIS,
                1000L * Math.max(1, preferencesService != null ? preferencesService.getInt("llm.timeout.seconds", 60) : 60));
        for (int attempt = 0; ; attempt++) {
            int currentAttempt = attempt;
            try {
                return requestAdmission.execute(budget, () -> {
                    AnalysisRunControl.phase(count ? "LLM_TOKEN_COUNT" : "LLM_REQUEST", null);
                    java.util.function.Supplier<ResponseEntity<String>> operation = () -> responsesTransport.exchange(
                            count ? OpenAiResponsesTokenContract.COUNT_URL : OpenAiResponsesTokenContract.GENERATION_URL,
                            headers, body, count ? OpenAiResponsesTokenContract.MAX_COUNT_RESPONSE_BYTES
                                    : OpenAiResponsesTokenContract.MAX_GENERATION_RESPONSE_BYTES, timeout);
                    return count ? LlmTransportMeter.exchangeCount(invocation, provider.name(), currentAttempt, objectMapper, operation)
                            : LlmTransportMeter.exchange(invocation, provider.name(), currentAttempt, objectMapper, operation);
                });
            } catch (HttpClientErrorException failure) {
                int status = failure.getStatusCode().value();
                if (status == 429) {
                    if (requestAdmission.retryRateLimit(failure, objectMapper, attempt, maxRetries)) continue;
                    throw new LlmRateLimitException(provider + " rate limit (HTTP 429)", failure);
                }
                throw new LlmProviderException(status == 401 || status == 403 ? LlmProviderException.Reason.AUTHENTICATION
                        : LlmProviderException.Reason.REQUEST_REJECTED,
                        provider + " Responses endpoint rejected the request (HTTP " + status + ")", failure);
            } catch (HttpServerErrorException failure) {
                if (attempt < maxRetries) { AnalysisRunControl.pause("RETRY_WAIT", ProviderRetryPolicy.backoffMillis(attempt)); continue; }
                throw new LlmProviderException(LlmProviderException.Reason.REQUEST_REJECTED,
                        provider + " Responses endpoint returned a server error", failure);
            } catch (ResourceAccessException failure) {
                if (failure.getCause() instanceof SocketTimeoutException) {
                    if (attempt < maxRetries) { AnalysisRunControl.pause("RETRY_WAIT", ProviderRetryPolicy.backoffMillis(attempt)); continue; }
                    throw new LlmTimeoutException(provider + " Responses operation exceeded its fixed time limit", failure);
                }
                throw new LlmProviderException(LlmProviderException.Reason.ENDPOINT_UNREACHABLE,
                        provider + " Responses endpoint is unreachable", failure);
            }
        }
    }

    private void validateConfiguration() {
        if (provider != LlmProvider.CUSTOM_OPENAI) return;
        LlmProviderConfig.CustomOpenAiConfigurationStatus status =
                configuredCustomStatus != null ? configuredCustomStatus
                        : LlmProviderConfig.validateCustomOpenAiConfiguration(url, model);
        if (!status.valid()) {
            throw new LlmProviderException(LlmProviderException.Reason.CONFIGURATION, status.message());
        }
    }

    /** Kept for direct throttle contracts; production admission surrounds every HTTP attempt. */
    void throttle() { requestAdmission.execute(() -> null); }

    private void applyCurrentTimeout() {
        if (preferencesService == null || llmRequestFactory == null) return;
        int timeoutSeconds = preferencesService.getInt("llm.timeout.seconds", 60);
        llmRequestFactory.setReadTimeout(timeoutSeconds * 1000);
    }
}
