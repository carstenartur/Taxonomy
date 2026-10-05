package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Per-pod live observers. Durable store state remains authoritative after any delivery gap. */
public final class ClusterAnalysisSignals {
    private final CopyOnWriteArrayList<Subscription> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Worker> workers = new CopyOnWriteArrayList<>();
    private final Clock clock;
    private volatile java.util.function.Predicate<AnalysisOperationContext> terminal = context -> false;
    private volatile Consumer<Duration> cancellationLatency = latency -> { };

    public ClusterAnalysisSignals() { this(Clock.systemUTC()); }
    ClusterAnalysisSignals(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public void cancellationLatency(Consumer<Duration> observer) {
        cancellationLatency = Objects.requireNonNull(observer);
    }

    public void workerReconciliation(java.util.function.Predicate<AnalysisOperationContext> terminal) {
        this.terminal = Objects.requireNonNull(terminal);
    }

    public Subscription listen(AnalysisOperationContext context, Consumer<AnalysisProgressEvent> consumer) {
        var subscription = new Subscription(context, consumer); listeners.add(subscription); return subscription;
    }
    public Worker worker(AnalysisOperationContext context) {
        var worker = new Worker(context); workers.add(worker); return worker;
    }
    public void progress(AnalysisProgressEvent event) {
        if (event.phase() == AnalysisProgressPhase.OPERATION_STOPPED) {
            var context = ClusterAnalysisStore.context(event.envelope());
            for (var worker : workers) if (worker.context.equals(context)) worker.stop(event.envelope().createdAt());
        }
        for (var listener : listeners) listener.deliver(event);
    }
    public void cancellation(AnalysisCancellationEvent event) {
        var context = ClusterAnalysisStore.context(event.envelope());
        for (var worker : workers) if (worker.context.equals(context)) worker.stop(event.envelope().createdAt());
    }
    /** Connection events wake observers to reconcile once with the durable snapshot. */
    public void reconnected() {
        for (var worker : workers) worker.reconcile();
        for (var listener : listeners) listener.reconnected();
    }
    public int listenerCount() { return listeners.size(); }
    public int workerCount() { return workers.size(); }

    public final class Subscription implements AutoCloseable {
        private final AnalysisOperationContext context;
        private final Consumer<AnalysisProgressEvent> consumer;
        private final AtomicLong sequence = new AtomicLong();
        private volatile Runnable reconnect = () -> { };
        private Subscription(AnalysisOperationContext context, Consumer<AnalysisProgressEvent> consumer) {
            this.context = Objects.requireNonNull(context); this.consumer = Objects.requireNonNull(consumer);
        }
        public void onReconnect(Runnable action) { reconnect = Objects.requireNonNull(action); }
        private synchronized void deliver(AnalysisProgressEvent event) {
            if (!context.equals(ClusterAnalysisStore.context(event.envelope())) || event.sequence() <= sequence.get()) return;
            sequence.set(event.sequence());
            try { consumer.accept(event); } catch (RuntimeException ignored) { /* One disconnected observer cannot block others. */ }
        }
        private void reconnected() { try { reconnect.run(); } catch (RuntimeException ignored) { } }
        @Override public void close() { listeners.remove(this); }
    }
    public final class Worker implements AutoCloseable {
        private final AnalysisOperationContext context;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean observationRecorded = new AtomicBoolean();
        private volatile Instant requestedAt;
        private Worker(AnalysisOperationContext context) { this.context = Objects.requireNonNull(context); }
        public boolean cancelled() {
            boolean stopped = cancelled.get();
            Instant requested = requestedAt;
            if (stopped && requested != null && observationRecorded.compareAndSet(false, true)) {
                try { cancellationLatency.accept(Duration.between(requested, clock.instant())); }
                catch (RuntimeException ignored) { /* Metrics cannot prevent cooperative stopping. */ }
            }
            return stopped;
        }
        private synchronized void stop(Instant requested) {
            if (cancelled.get()) return;
            requestedAt = requested;
            cancelled.set(true);
        }
        private void reconcile() {
            try { if (terminal.test(context)) stop(null); }
            catch (RuntimeException unavailable) {
                // If authority cannot be revalidated after a gap, stop further external work.
                // A terminal result still joins the ledger and is fenced by current durable state.
                stop(null);
            }
        }
        @Override public void close() { workers.remove(this); }
    }
}
