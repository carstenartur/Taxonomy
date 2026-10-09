package com.taxonomy.analysis.service;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.OptionalLong;
import java.util.Set;

/** Bounded 429 handling; a long Retry-After is retained as cooldown, never shortened. */
public final class ProviderRetryPolicy {
    private static final long MAX_AUTOMATIC_WAIT_MILLIS = 60_000L;
    private static final Set<String> PERMANENT_QUOTA_CODES = Set.of(
            "insufficient_quota", "billing_hard_limit_reached", "billing_not_active");
    private ProviderRetryPolicy() { }

    public record Decision(boolean retry, long delayMillis) { }

    public static Decision rateLimit(int attempt, int maxRetries, String retryAfter,
                                    String errorCode, Instant now) {
        if (PERMANENT_QUOTA_CODES.contains(errorCode == null ? "" : errorCode)) {
            return new Decision(false, 0L);
        }
        long delay = retryAfterMillis(retryAfter, now).orElseGet(() -> backoffMillis(attempt));
        return new Decision(attempt < Math.max(0, maxRetries) && delay <= MAX_AUTOMATIC_WAIT_MILLIS, delay);
    }

    public static Decision rateLimit(int attempt, int maxRetries, Duration retryAfter) {
        long delay;
        if (retryAfter == null) delay = backoffMillis(attempt);
        else {
            if (retryAfter.isNegative()) throw new IllegalArgumentException("Negative retry delay");
            try { delay = retryAfter.toMillis(); }
            catch (ArithmeticException overflow) { delay = Long.MAX_VALUE; }
        }
        return new Decision(attempt < Math.max(0, maxRetries) && delay <= MAX_AUTOMATIC_WAIT_MILLIS, delay);
    }

    public static long backoffMillis(int attempt) {
        return Math.min(MAX_AUTOMATIC_WAIT_MILLIS, 1000L << Math.max(0, Math.min(6, attempt)));
    }

    private static OptionalLong retryAfterMillis(String header, Instant now) {
        if (header == null || header.isBlank()) return OptionalLong.empty();
        String value = header.strip();
        if (value.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            // Avoid both integer overflow and parsing an unbounded integer from an HTTP peer.
            if (value.length() > 19) return OptionalLong.of(Long.MAX_VALUE);
            BigInteger millis = new BigInteger(value).multiply(BigInteger.valueOf(1000));
            return OptionalLong.of(millis.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue());
        }
        if (value.length() > 128) return OptionalLong.empty();
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            if (!retryAt.isAfter(now)) return OptionalLong.of(0L);
            try { return OptionalLong.of(Duration.between(now, retryAt).toMillis()); }
            catch (ArithmeticException overflow) { return OptionalLong.of(Long.MAX_VALUE); }
        } catch (DateTimeParseException invalid) {
            return OptionalLong.empty();
        }
    }
}
