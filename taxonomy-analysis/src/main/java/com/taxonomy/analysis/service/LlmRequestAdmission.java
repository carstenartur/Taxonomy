package com.taxonomy.analysis.service;

import com.taxonomy.extension.api.llm.ProviderId;
import java.time.Duration;

import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.ObjectMapper;

/** Connects the shared provider policy to existing cancellation, settings and telemetry. */
final class LlmRequestAdmission {
    private final ProviderId provider;
    private final int defaultRpm;
    private final AnalysisRuntimeSettings settings;
    private ProviderRequestLimiter.Limits limits = new ProviderRequestLimiter.Limits(4, 64);
    private ProviderRequestLimiter limiter = new ProviderRequestLimiter(limits);
    private ProviderConcurrencyPermits clusterPermits = ProviderConcurrencyPermits.NONE;
    private boolean used;

    LlmRequestAdmission(LlmProvider provider, int defaultRpm, AnalysisRuntimeSettings settings) {
        this(provider.id(), defaultRpm, settings);
    }

    LlmRequestAdmission(ProviderId provider, int defaultRpm, AnalysisRuntimeSettings settings) {
        this.provider = provider;
        this.defaultRpm = defaultRpm;
        this.settings = settings;
    }

    /** Application-startup configuration only; an active queue is never replaced. */
    synchronized void configure(ProviderRequestLimiter.Limits limits) {
        if (used) throw new IllegalStateException("Cannot replace an active provider admission policy");
        this.limits = limits;
        limiter = new ProviderRequestLimiter(limits);
    }

    synchronized void configure(ProviderConcurrencyPermits permits) {
        if (used) throw new IllegalStateException("Cannot replace an active provider permit policy");
        clusterPermits = java.util.Objects.requireNonNull(permits, "permits");
    }

    <T> T execute(Supplier<T> request) {
        ProviderRequestLimiter current;
        ProviderConcurrencyPermits distributed;
        long maximumWait;
        synchronized (this) {
            used = true;
            current = limiter;
            distributed = clusterPermits;
            maximumWait = limits.maximumWaitMillis();
        }
        String key = provider.equals(LlmProvider.GEMINI.id()) ? "llm.rpm"
                : "llm.rpm." + provider.value().toLowerCase(Locale.ROOT);
        int rpm = settings == null ? 0 : Math.max(0, settings.getInt(key, defaultRpm));
        long queuedAt = System.nanoTime();
        Runnable checkpoint = () -> {
            AnalysisRunControl.checkpoint();
            if (distributed != ProviderConcurrencyPermits.NONE
                    && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt) >= maximumWait) {
                throw new LlmRateLimitException(provider + ": Provider admission wait expired");
            }
        };
        while (true) {
            ProviderRequestLimiter.Permit permit;
            try {
                permit = current.acquire(rpm, checkpoint, (reason, millis) ->
                        AnalysisRunControl.pause(reason == ProviderRequestLimiter.WaitReason.CAPACITY
                                ? "WAITING_PROVIDER_CAPACITY" : "WAITING_RATE_LIMIT", millis));
            } catch (ProviderRequestLimiter.CapacityException | ProviderRequestLimiter.WaitTimeoutException full) {
                throw new LlmRateLimitException(provider + ": " + full.getMessage(), full);
            }
            boolean requestStarted = false;
            try {
                if (distributed == ProviderConcurrencyPermits.NONE) return request.get();
                AnalysisRunControl.phase("WAITING_PROVIDER_CAPACITY", null);
                try (var clusterPermit = distributed.acquire(provider, checkpoint)) {
                    checkpoint.run();
                    if (!permit.refreshRateAdmission(rpm)) continue;
                    requestStarted = true;
                    return request.get();
                }
            } finally {
                if (distributed != ProviderConcurrencyPermits.NONE && !requestStarted) permit.cancelRateReservation();
                permit.close();
            }
        }
    }

    boolean retryRateLimit(Duration retryAfter, int attempt, int maxRetries) {
        var decision = ProviderRetryPolicy.rateLimit(attempt, maxRetries, retryAfter);
        synchronized (this) { limiter.deferFor(decision.delayMillis()); }
        if (decision.retry()) AnalysisRunControl.phase("WAITING_RATE_LIMIT", null);
        return decision.retry();
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
