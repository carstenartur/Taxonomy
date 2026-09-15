package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Request/worker-scoped callbacks; no global prompt, result or credential retention. */
public final class AnalysisRunControl implements AutoCloseable {
    interface Observer {
        void phase(String phase, String node);
        long started(String provider, String node);
        void completed(long callId, LlmCallDetail detail, long durationMillis);
        void failed(long callId, String failure, long durationMillis);
        void stopped(AnalysisStoppedException.Reason reason);
    }

    private static final ThreadLocal<AnalysisRunControl> CURRENT = new ThreadLocal<>();
    private final AnalysisRunControl previous;
    private final Observer observer;
    private final BooleanSupplier cancelled;
    private final AnalysisMemoryGuard guard;
    private boolean closed;

    AnalysisRunControl(Observer observer, BooleanSupplier cancelled, AnalysisMemoryGuard guard) {
        this.previous = CURRENT.get();
        this.observer = observer;
        this.cancelled = cancelled;
        this.guard = guard;
        CURRENT.set(this);
    }

    public static boolean active() { return CURRENT.get() != null; }

    public static void checkpoint() {
        AnalysisRunControl current = CURRENT.get();
        try {
            if (Thread.currentThread().isInterrupted()
                    || (current != null && current.cancelled.getAsBoolean())) {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED);
            }
            if (current != null) current.guard.check();
        } catch (AnalysisStoppedException stopped) {
            if (current != null) current.observer.stopped(stopped.reason());
            throw stopped;
        }
    }

    public static void phase(String phase, String node) {
        checkpoint();
        AnalysisRunControl current = CURRENT.get();
        if (current != null) current.observer.phase(phase, node);
    }

    public static LlmCallDetail call(String provider, String node, Supplier<LlmCallDetail> operation) {
        checkpoint();
        var current = CURRENT.get();
        long id = current == null ? 0 : current.observer.started(provider, node);
        long started = System.nanoTime();
        LlmCallDetail detail;
        try {
            detail = operation.get();
        } catch (AnalysisStoppedException stopped) {
            // A cooperative stop is not a provider failure, including stops inside retries.
            if (current != null) current.observer.stopped(stopped.reason());
            throw stopped;
        } catch (RuntimeException failure) {
            if (current != null) current.observer.failed(id, failure.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            throw failure;
        }
        try {
            // The final provider call may outlive cancellation, the deadline or heap reserves.
            checkpoint();
        } catch (AnalysisStoppedException stopped) {
            throw stopped.withPartial(detail);
        }
        if (current != null) current.observer.completed(id, detail, (System.nanoTime() - started) / 1_000_000);
        return detail;
    }

    public static void pause(String phase, long millis) {
        phase(phase, null);
        long remaining = Math.max(0, millis);
        while (remaining > 0) {
            checkpoint();
            long step = Math.min(250, remaining);
            try {
                Thread.sleep(step);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                checkpoint();
            }
            remaining -= step;
        }
        checkpoint();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
