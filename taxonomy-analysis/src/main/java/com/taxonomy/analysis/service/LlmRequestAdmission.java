package com.taxonomy.analysis.service;

import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

/** Connects the shared provider policy to existing cancellation, settings and telemetry. */
final class LlmRequestAdmission {
    private final LlmProvider provider;
    private final int defaultRpm;
    private final AnalysisRuntimeSettings settings;
    private ProviderRequestLimiter limiter = new ProviderRequestLimiter(new ProviderRequestLimiter.Limits(4, 64));
    private boolean used;

    LlmRequestAdmission(LlmProvider provider, int defaultRpm, AnalysisRuntimeSettings settings) {
        this.provider = provider;
        this.defaultRpm = defaultRpm;
        this.settings = settings;
    }

    /** Application-startup configuration only; an active queue is never replaced. */
    synchronized void configure(ProviderRequestLimiter.Limits limits) {
        if (used) throw new IllegalStateException("Cannot replace an active provider admission policy");
        limiter = new ProviderRequestLimiter(limits);
    }

    <T> T execute(Supplier<T> request) {
        ProviderRequestLimiter current;
        synchronized (this) { used = true; current = limiter; }
        String key = provider == LlmProvider.GEMINI ? "llm.rpm"
                : "llm.rpm." + provider.name().toLowerCase(Locale.ROOT);
        int rpm = settings == null ? 0 : Math.max(0, settings.getInt(key, defaultRpm));
        ProviderRequestLimiter.Permit permit;
        try {
            permit = current.acquire(rpm, AnalysisRunControl::checkpoint, (reason, millis) ->
                    AnalysisRunControl.pause(reason == ProviderRequestLimiter.WaitReason.CAPACITY
                            ? "WAITING_PROVIDER_CAPACITY" : "WAITING_RATE_LIMIT", millis));
        } catch (ProviderRequestLimiter.CapacityException | ProviderRequestLimiter.WaitTimeoutException full) {
            throw new LlmRateLimitException(provider + ": " + full.getMessage(), full);
        }
        try { return request.get(); }
        finally { permit.close(); }
    }

    boolean retryRateLimit(HttpClientErrorException failure, ObjectMapper mapper, int attempt, int maxRetries) {
        String code = "";
        try {
            var error = mapper.readTree(failure.getResponseBodyAsString()).path("error").path("code");
            if (error.isString()) code = error.stringValue();
        } catch (RuntimeException invalidEnvelope) {
            // Unknown envelopes still have a bounded retry budget; never log their body.
        }
        String retryAfter = failure.getResponseHeaders() == null ? null
                : failure.getResponseHeaders().getFirst("Retry-After");
        var decision = ProviderRetryPolicy.rateLimit(attempt, maxRetries, retryAfter, code, Instant.now());
        synchronized (this) { limiter.deferFor(decision.delayMillis()); }
        if (decision.retry()) AnalysisRunControl.phase("WAITING_RATE_LIMIT", null);
        return decision.retry();
    }
}
