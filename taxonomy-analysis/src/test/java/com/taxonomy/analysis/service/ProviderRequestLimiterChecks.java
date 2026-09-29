package com.taxonomy.analysis.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ProviderRequestLimiterChecks {
    private ProviderRequestLimiterChecks() { }
    @SuppressWarnings("try") // Several checks intentionally release capacity before lexical scope exit.
    public static Map<String, Runnable> checks() {
        Map<String, Runnable> checks = new LinkedHashMap<>();
        checks.put("every physical attempt including a retry consumes RPM", () -> {
            AtomicLong clock = new AtomicLong();
            var limiter = limiter(2, 4, clock);
            AtomicInteger waits = new AtomicInteger();
            ProviderRequestLimiter.Waiter waiter = (reason, millis) -> {
                equal(ProviderRequestLimiter.WaitReason.RATE_LIMIT, reason);
                waits.incrementAndGet(); clock.addAndGet(millis);
            };
            try (var first = limiter.acquire(1, () -> { }, waiter)) { }
            try (var retry = limiter.acquire(1, () -> { }, waiter)) { }
            check(waits.get() > 0 && clock.get() >= 60_000L, "retry bypassed RPM");
            equal(0, limiter.inFlight());
        });
        checks.put("concurrent requests are bounded independently of RPM", () -> {
            AtomicLong clock = new AtomicLong();
            var limiter = limiter(1, 2, clock);
            var first = limiter.acquire(0, () -> { }, failWait());
            AtomicInteger waits = new AtomicInteger();
            try (var second = limiter.acquire(0, () -> { }, (reason, millis) -> {
                equal(ProviderRequestLimiter.WaitReason.CAPACITY, reason);
                waits.incrementAndGet(); first.close();
            })) {
                equal(1, limiter.inFlight());
            }
            equal(1, waits.get()); equal(0, limiter.inFlight());
        });
        checks.put("cancelled waiting request consumes no future quota", () -> {
            AtomicLong clock = new AtomicLong();
            var limiter = limiter(1, 2, clock);
            try (var first = limiter.acquire(1, () -> { }, failWait())) { }
            try {
                limiter.acquire(1, () -> { }, (reason, millis) -> { throw new Cancelled(); });
                throw new AssertionError("Expected cancellation");
            } catch (Cancelled expected) { }
            equal(0, limiter.waiting());
            clock.set(60_001L);
            try (var next = limiter.acquire(1, () -> { }, failWait())) { }
        });
        checks.put("cancellation is checked before admission", () -> {
            var limiter = limiter(1, 2, new AtomicLong());
            try {
                limiter.acquire(1, () -> { throw new Cancelled(); }, failWait());
                throw new AssertionError("Expected cancellation");
            } catch (Cancelled expected) { }
            equal(0, limiter.inFlight()); equal(0, limiter.waiting());
            try (var next = limiter.acquire(1, () -> { }, failWait())) { }
        });
        checks.put("a shared provider cooldown affects the next job", () -> {
            AtomicLong clock = new AtomicLong();
            var limiter = limiter(2, 4, clock);
            limiter.deferFor(1200);
            try (var next = limiter.acquire(0, () -> { }, (reason, millis) -> {
                equal(ProviderRequestLimiter.WaitReason.PROVIDER_BACKOFF, reason);
                equal(0, limiter.inFlight());
                clock.addAndGet(millis);
            })) { check(clock.get() >= 1200L, "cooldown not respected"); }
        });
        checks.put("one provider cooldown does not block another provider", () -> {
            AtomicLong clock = new AtomicLong();
            var first = limiter(1, 2, clock);
            var second = limiter(1, 2, clock);
            first.deferFor(10_000);
            try (var permit = second.acquire(1, () -> { }, failWait())) { equal(1, second.inFlight()); }
        });
        checks.put("exception cleanup releases the permit exactly once", () -> {
            var limiter = limiter(1, 2, new AtomicLong());
            try (var permit = limiter.acquire(0, () -> { }, failWait())) { throw new Cancelled(); }
            catch (Cancelled expected) { }
            var next = limiter.acquire(0, () -> { }, failWait());
            next.close(); next.close();
            equal(0, limiter.inFlight());
        });
        checks.put("provider waiting queue is bounded", () -> {
            var limiter = limiter(1, 1, new AtomicLong());
            var active = limiter.acquire(0, () -> { }, failWait());
            try (var waiting = limiter.acquire(0, () -> { }, (reason, millis) -> {
                try {
                    limiter.acquire(0, () -> { }, failWait());
                    throw new AssertionError("Expected a full queue");
                } catch (ProviderRequestLimiter.CapacityException expected) { }
                active.close();
            })) { equal(1, limiter.inFlight()); }
            equal(0, limiter.waiting());
        });
        checks.put("provider waiting has a deadline even without an analysis context", () -> {
            AtomicLong clock = new AtomicLong();
            var limiter = limiter(1, 2, clock);
            limiter.deferFor(1_000_000L);
            try {
                limiter.acquire(0, () -> { }, (reason, millis) -> clock.addAndGet(millis));
                throw new AssertionError("Expected bounded wait");
            } catch (ProviderRequestLimiter.WaitTimeoutException expected) { }
            equal(0, limiter.waiting()); equal(0, limiter.inFlight());
        });
        checks.put("provider policies reject invalid limits", () -> {
            invalid(() -> limiter(0, 2, new AtomicLong()));
            invalid(() -> limiter(65, 2, new AtomicLong()));
            invalid(() -> limiter(1, 0, new AtomicLong()));
            invalid(() -> limiter(1, 10001, new AtomicLong()));
        });
        return checks;
    }
    private static ProviderRequestLimiter limiter(int active, int waiting, AtomicLong clock) {
        return new ProviderRequestLimiter(new ProviderRequestLimiter.Limits(active, waiting), clock::get);
    }
    private static ProviderRequestLimiter.Waiter failWait() {
        return (reason, millis) -> { throw new AssertionError("Unexpected wait: " + reason); };
    }
    private static void invalid(Runnable run) {
        try { run.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected invalid policy");
    }
    private static final class Cancelled extends RuntimeException { private static final long serialVersionUID = 1L; }
    private static void equal(Object expected, Object actual) {
        check(java.util.Objects.equals(expected, actual), "expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) {
        checks().forEach((name, test) -> { test.run(); System.out.println("PASS " + name); });
    }
}
