package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.Objects;

/** Request/worker-scoped callbacks; no global prompt, result or credential retention. */
public final class AnalysisRunControl implements AutoCloseable {
    interface Observer {
        void phase(String phase, String node);
        long started(String provider, String node);
        default void prepared(long callId, String prompt) { }
        void completed(long callId, LlmCallDetail detail, long durationMillis);
        void failed(long callId, String failure, long durationMillis);
        void stopped(AnalysisStoppedException.Reason reason);
        default void stoppedAfterResponse(long callId, LlmCallDetail detail, long durationMillis,
                                          AnalysisStoppedException.Reason reason) {
            stopped(reason);
        }
    }

    private static final ThreadLocal<AnalysisRunControl> CURRENT = new ThreadLocal<>();
    private final AnalysisRunControl previous;
    private final Observer observer;
    private final BooleanSupplier cancelled;
    private final AnalysisMemoryGuard guard;
    private boolean closed;
    // Thread-confined identity only: the observer owns the bounded prompt preview.
    private long activeCallId = -1;

    AnalysisRunControl(Observer observer, BooleanSupplier cancelled, AnalysisMemoryGuard guard) {
        this.previous = CURRENT.get();
        this.observer = observer;
        this.cancelled = cancelled;
        this.guard = guard;
        CURRENT.set(this);
    }

    public static boolean active() { return CURRENT.get() != null; }

    /** Records prepared request evidence, not proof that a provider received it. */
    static void preparedPrompt(String prompt) {
        var current = CURRENT.get();
        if (current != null && current.activeCallId >= 0) {
            current.observer.prepared(current.activeCallId, prompt);
        }
    }

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
        return call(provider, node, operation, Function.identity());
    }

    /** Run a typed assessment through the existing cancellation, memory and evidence path. */
    public static <T> T call(String provider, String node, Supplier<T> operation,
                             Function<? super T, LlmCallDetail> describe) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(describe, "describe");
        checkpoint();
        var current = CURRENT.get();
        long id = current == null ? 0 : current.observer.started(provider, node);
        long started = System.nanoTime();
        long previousCallId = current == null ? -1 : current.activeCallId;
        if (current != null) current.activeCallId = id;
        T result;
        LlmCallDetail detail;
        try {
            result = Objects.requireNonNull(operation.get(), "assessment result");
            detail = Objects.requireNonNull(describe.apply(result), "assessment evidence");
        } catch (AnalysisStoppedException stopped) {
            // A cooperative stop is not a provider failure, including stops inside retries.
            if (current != null) current.observer.stopped(stopped.reason());
            throw stopped;
        } catch (RuntimeException failure) {
            // A provider exception must not hide a stop that arrived during its call.
            checkpoint();
            if (current != null) current.observer.failed(id, failure.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            throw failure;
        } finally {
            // Restore nesting and prevent later, unobserved gateway calls from
            // attaching their prompt to this completed, failed or stopped call.
            if (current != null) current.activeCallId = previousCallId;
        }
        try {
            // The final provider call may outlive cancellation, the deadline or heap reserves.
            checkpoint();
        } catch (AnalysisStoppedException stopped) {
            if (current != null) current.observer.stoppedAfterResponse(
                    id, detail, (System.nanoTime() - started) / 1_000_000, stopped.reason());
            throw stopped.withPartial(detail);
        }
        if (current != null) current.observer.completed(id, detail, (System.nanoTime() - started) / 1_000_000);
        return result;
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
