package com.taxonomy.analysis.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProviderRetryPolicyChecks {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private ProviderRetryPolicyChecks() { }
    public static Map<String, Runnable> checks() {
        Map<String, Runnable> checks = new LinkedHashMap<>();
        checks.put("Retry-After seconds are respected", () -> {
            var decision = ProviderRetryPolicy.rateLimit(0, 2, "5", "rate_limit_exceeded", NOW);
            check(decision.retry() && decision.delayMillis() == 5000, "delta seconds");
        });
        checks.put("Retry-After HTTP dates are respected", () -> {
            var decision = ProviderRetryPolicy.rateLimit(0, 2, "Tue, 29 Sep 2026 12:00:10 GMT", "", NOW);
            check(decision.retry() && decision.delayMillis() == 10000, "HTTP date");
        });
        checks.put("invalid Retry-After uses bounded exponential backoff", () -> {
            var decision = ProviderRetryPolicy.rateLimit(1, 2, "-5", "", NOW);
            check(decision.retry() && decision.delayMillis() == 2000, "fallback");
        });
        checks.put("past Retry-After dates do not produce negative sleeps", () -> {
            var decision = ProviderRetryPolicy.rateLimit(0, 2, "Tue, 29 Sep 2026 11:59:59 GMT", "", NOW);
            check(decision.retry() && decision.delayMillis() == 0, "past date");
        });
        checks.put("exhausted billing quota is not retried", () -> {
            for (String code : new String[]{"insufficient_quota", "billing_hard_limit_reached", "billing_not_active"}) {
                var decision = ProviderRetryPolicy.rateLimit(0, 2, "1", code, NOW);
                check(!decision.retry() && decision.delayMillis() == 0, "permanent quota " + code);
            }
        });
        checks.put("429 retries stop at the configured attempt budget", () -> {
            var decision = ProviderRetryPolicy.rateLimit(2, 2, "5", "", NOW);
            check(!decision.retry() && decision.delayMillis() == 5000, "budget and shared cooldown");
        });
        checks.put("long waits are never shortened into an early retry", () -> {
            var decision = ProviderRetryPolicy.rateLimit(0, 2, "600", "", NOW);
            check(!decision.retry() && decision.delayMillis() == 600000, "long cooldown");
        });
        checks.put("overflowing Retry-After cannot become an immediate retry", () -> {
            var decision = ProviderRetryPolicy.rateLimit(0, 2, "999999999999999999999999999999", "", NOW);
            check(!decision.retry() && decision.delayMillis() == Long.MAX_VALUE, "overflow");
        });
        checks.put("backoff arithmetic stays bounded", () -> {
            check(ProviderRetryPolicy.backoffMillis(Integer.MAX_VALUE) == 60000, "large attempt");
            check(ProviderRetryPolicy.backoffMillis(-1) == 1000, "negative attempt");
        });
        return checks;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        checks().forEach((name, test) -> { test.run(); System.out.println("PASS " + name); });
    }
}
