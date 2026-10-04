package com.taxonomy.analysis.service;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One process-local limiter per configured HTTP provider. Admission counts physical
 * attempts, including retries. Waiting holds neither an HTTP permit nor a lock
 * across callbacks, and retains no request body or credentials.
 */
public final class ProviderRequestLimiter {
    public enum WaitReason { CAPACITY, RATE_LIMIT, PROVIDER_BACKOFF }
    @FunctionalInterface public interface Waiter { void pause(WaitReason reason, long millis); }
    public record Limits(int maxConcurrent, int maxQueued, long maximumWaitMillis) {
        public Limits(int maxConcurrent, int maxQueued) { this(maxConcurrent, maxQueued, 120_000L); }
        public Limits {
            if (maximumWaitMillis < 1 || maximumWaitMillis > 86_400_000L) {
                throw new IllegalArgumentException("maximumWaitMillis must be between 1 and 86400000");
            }
            if (maxConcurrent < 1 || maxConcurrent > 64) {
                throw new IllegalArgumentException("maxConcurrent must be between 1 and 64");
            }
            if (maxQueued < 1 || maxQueued > 10_000) {
                throw new IllegalArgumentException("maxQueued must be between 1 and 10000");
            }
        }
    }
    public static final class CapacityException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private CapacityException() { super("Local provider waiting queue is full"); }
    }

    public static final class WaitTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private WaitTimeoutException() { super("Local provider admission wait expired"); }
    }

    private static final long WINDOW_MILLIS = 60_000L;
    private final Limits limits;
    private final LongSupplier monotonicMillis;
    private final ArrayDeque<Object> waiting = new ArrayDeque<>();
    private final ArrayDeque<RateReservation> starts = new ArrayDeque<>();
    private int inFlight;
    private long notBefore = Long.MIN_VALUE;

    public ProviderRequestLimiter(Limits limits) {
        this(limits, () -> System.nanoTime() / 1_000_000L);
    }
    ProviderRequestLimiter(Limits limits, LongSupplier monotonicMillis) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.monotonicMillis = Objects.requireNonNull(monotonicMillis, "monotonicMillis");
    }

    public Permit acquire(int requestsPerMinute, Runnable checkpoint, Waiter waiter) {
        if (requestsPerMinute < 0 || requestsPerMinute > 100_000) {
            throw new IllegalArgumentException("requestsPerMinute must be between 0 and 100000");
        }
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(waiter, "waiter");
        checkpoint.run();
        long queuedAt = monotonicMillis.getAsLong();
        Object ticket = new Object();
        synchronized (this) {
            if (waiting.size() >= limits.maxQueued()) throw new CapacityException();
            waiting.addLast(ticket);
        }
        try {
            while (true) {
                checkpoint.run();
                WaitReason reason;
                long pauseMillis = 250L;
                synchronized (this) {
                    long now = monotonicMillis.getAsLong();
                    expireStarts(now);
                    boolean rateAvailable = requestsPerMinute == 0 || starts.size() < requestsPerMinute;
                    boolean capacityAvailable = inFlight < limits.maxConcurrent() && waiting.peekFirst() == ticket;
                    if (capacityAvailable && rateAvailable && now >= notBefore) {
                        waiting.removeFirst();
                        RateReservation reservation = requestsPerMinute > 0 ? new RateReservation(now) : null;
                        if (reservation != null) starts.addLast(reservation);
                        inFlight++;
                        return new Permit(reservation);
                    }
                    if (now - queuedAt >= limits.maximumWaitMillis()) throw new WaitTimeoutException();
                    if (now < notBefore) {
                        reason = WaitReason.PROVIDER_BACKOFF;
                    } else if (!capacityAvailable) {
                        reason = WaitReason.CAPACITY;
                    } else {
                        reason = WaitReason.RATE_LIMIT;
                        pauseMillis = Math.max(1L, Math.min(pauseMillis, WINDOW_MILLIS - (now - starts.peekFirst().at)));
                    }
                }
                // The application supplies cooperative cancellation/deadline checks.
                waiter.pause(reason, pauseMillis);
            }
        } finally {
            synchronized (this) { waiting.remove(ticket); }
        }
    }

    /** Share Retry-After across jobs without retaining a permit during the cooldown. */
    public synchronized void deferFor(long millis) {
        if (millis <= 0) return;
        long now = monotonicMillis.getAsLong();
        long deadline;
        try { deadline = Math.addExact(now, millis); }
        catch (ArithmeticException overflow) { deadline = Long.MAX_VALUE; }
        notBefore = Math.max(notBefore, deadline);
    }

    synchronized int inFlight() { return inFlight; }
    synchronized int waiting() { return waiting.size(); }

    private void expireStarts(long now) {
        while (!starts.isEmpty() && now - starts.peekFirst().at >= WINDOW_MILLIS) starts.removeFirst();
    }

    private static final class RateReservation {
        private final long at;
        private RateReservation(long at) { this.at = at; }
    }

    public final class Permit implements AutoCloseable {
        private boolean closed;
        private RateReservation reservation;
        private Permit(RateReservation reservation) { this.reservation = reservation; }

        /**
         * Revalidate after waiting for external capacity. A stale admission must not
         * shift physical calls outside the RPM window, or bypass a concurrent 429.
         * On false, return external capacity before waiting for local admission again.
         */
        boolean refreshRateAdmission(int requestsPerMinute) {
            synchronized (ProviderRequestLimiter.this) {
                if (closed) throw new IllegalStateException("Provider permit already closed");
                long now = monotonicMillis.getAsLong();
                expireStarts(now);
                if (reservation != null) starts.remove(reservation);
                reservation = null;
                if (now < notBefore || (requestsPerMinute > 0 && starts.size() >= requestsPerMinute)) return false;
                if (requestsPerMinute > 0) {
                    reservation = new RateReservation(now);
                    starts.addLast(reservation);
                }
                return true;
            }
        }

        /** A denied/cancelled external admission never started a physical request. */
        void cancelRateReservation() {
            synchronized (ProviderRequestLimiter.this) {
                if (reservation != null) starts.remove(reservation);
                reservation = null;
            }
        }
        @Override public void close() {
            synchronized (ProviderRequestLimiter.this) {
                if (closed) return;
                closed = true;
                inFlight--;
            }
        }
    }
}
