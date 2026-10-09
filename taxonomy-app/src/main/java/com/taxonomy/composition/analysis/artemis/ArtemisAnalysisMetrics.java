package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Process observations at the existing execution boundaries, without database scans.
 * Labels contain only finite task/phase/outcome vocabulary, the eight catalogue roots,
 * and configured provider quota groups. No operation, task, source or user identities
 * are exposed. Preparation outcomes describe computation, not committed durable effects.
 */
public final class ArtemisAnalysisMetrics {
    private static final String PREFIX = "taxonomy.analysis.";
    private final MeterRegistry registry;
    private final ConcurrentHashMap<Tags, Activity> tasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Tags, Activity> providers = new ConcurrentHashMap<>();
    private final ThreadLocal<CompletionTiming> coordinating = new ThreadLocal<>();
    private record CompletionTiming(String operationId, Instant createdAt) { }

    public ArtemisAnalysisMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /** Includes only preparation; the returned persistence callback remains untouched. */
    public <T extends AnalysisCompletionMessage> PreparedAnalysisCompletion<T> prepare(
            AnalysisTaskMessage task, Supplier<PreparedAnalysisCompletion<T>> computation) {
        Objects.requireNonNull(task); Objects.requireNonNull(computation);
        Tags tags = taskTags(task);
        recordAge("task.queue.wait", tags, task.envelope().createdAt());
        Activity activity = tasks.computeIfAbsent(tags, key -> new Activity("task", key, false));
        activity.enter();
        long started = registry.config().clock().monotonicTime();
        String outcome = "FAILED";
        try {
            var prepared = Objects.requireNonNull(computation.get());
            outcome = outcome(prepared.completion());
            return prepared;
        } catch (AnalysisStoppedException stopped) {
            outcome = "STOPPED";
            throw stopped;
        } finally {
            activity.leave();
            Tags result = tags.and("outcome", outcome);
            recordDuration("task.execution", result, started);
            registry.counter(PREFIX + "task.outcomes", result).increment();
        }
    }

    /** Invoke from the durable event's after-commit publisher, not from live replay. */
    public void progress(AnalysisProgressEvent event) {
        registry.counter(PREFIX + "operation.events", "phase", event.phase().name()).increment();
    }

    /** First cooperative observation after live durable-control publication; reconnect invents no timestamp. */
    public void cancellationObserved(Duration latency) {
        Objects.requireNonNull(latency);
        registry.timer(PREFIX + "cancellation.observed").record(latency.isNegative() ? Duration.ZERO : latency);
    }

    /** Completion age includes DB result commit and broker transit, separately from callback duration. */
    public void coordination(AnalysisCompletionMessage completion, Runnable acceptance) {
        Objects.requireNonNull(completion); Objects.requireNonNull(acceptance);
        Tags tags = envelopeTags(completion.envelope());
        recordAge("completion.queue.wait", tags, completion.envelope().createdAt());
        CompletionTiming previous = coordinating.get();
        coordinating.set(new CompletionTiming(completion.envelope().operationId(), completion.envelope().createdAt()));
        long started = registry.config().clock().monotonicTime();
        String outcome = "FAILED";
        try {
            acceptance.run();
            outcome = "COMPLETED";
        } finally {
            if (previous == null) coordinating.remove(); else coordinating.set(previous);
            recordDuration("coordination.duration", tags.and("outcome", outcome), started);
        }
    }

    /**
     * Wrap the successful broker publish boundary. Immediate downstream sends on the
     * coordinator thread measure actual completion-to-dispatch latency; startup/repair
     * sends have no causative completion and therefore do not invent that measurement.
     */
    public void dispatched(AnalysisTaskMessage task, Runnable publication) {
        Objects.requireNonNull(task); Objects.requireNonNull(publication);
        Tags tags = taskTags(task);
        String outcome = "FAILED";
        try {
            publication.run();
            outcome = "COMPLETED";
            var timing = coordinating.get();
            if (timing != null && timing.operationId().equals(task.envelope().operationId()))
                recordAge("completion.to.dispatch", tags, timing.createdAt());
            if (task.taskType() == AnalysisTaskType.RELATION_ANALYSIS)
                registry.counter(PREFIX + "relation.dispatched", tags).increment();
        } finally {
            registry.counter(PREFIX + "task.dispatches", tags.and("outcome", outcome)).increment();
        }
    }

    /** Bind once to the role's long-lived transport instances; an absent role may pass null. */
    public void bindTransport(ArtemisAnalysisWorker worker, ArtemisClusterCoordinator coordinator) {
        if (worker != null) {
            FunctionCounter.builder(PREFIX + "worker.executions", worker, ArtemisAnalysisWorker::executions).register(registry);
            FunctionCounter.builder(PREFIX + "worker.replays", worker, ArtemisAnalysisWorker::idempotentReplays).register(registry);
            FunctionCounter.builder(PREFIX + "worker.rejected", worker, ArtemisAnalysisWorker::rejected).register(registry);
            FunctionCounter.builder(PREFIX + "worker.rollbacks", worker, ArtemisAnalysisWorker::rolledBack).register(registry);
        }
        if (coordinator != null) {
            FunctionCounter.builder(PREFIX + "coordinator.accepted", coordinator, ArtemisClusterCoordinator::accepted).register(registry);
            FunctionCounter.builder(PREFIX + "coordinator.failures", coordinator, ArtemisClusterCoordinator::failures).register(registry);
            FunctionCounter.builder(PREFIX + "coordinator.redeliveries", coordinator, ArtemisClusterCoordinator::redeliveries).register(registry);
        }
    }

    /** Observe actual permit acquisition and return, preserving cooperative cancellation and failures. */
    public ProviderConcurrencyPermits permits(ProviderConcurrencyPermits delegate, ArtemisProviderPermitSettings settings) {
        Objects.requireNonNull(delegate); Objects.requireNonNull(settings);
        return (provider, checkpoint) -> {
            Objects.requireNonNull(provider); Objects.requireNonNull(checkpoint);
            Tags tags = Tags.of("provider", provider.value(), "quota_group", settings.providerGroups().getOrDefault(provider, "unconfigured"));
            Activity activity = providers.computeIfAbsent(tags, key -> new Activity("provider", key, true));
            activity.waiting.incrementAndGet();
            long started = registry.config().clock().monotonicTime();
            String outcome = "FAILED";
            try {
                var permit = Objects.requireNonNull(delegate.acquire(provider, checkpoint));
                activity.enter();
                outcome = "ACQUIRED";
                long acquired = registry.config().clock().monotonicTime();
                var closed = new AtomicBoolean();
                return () -> {
                    if (!closed.compareAndSet(false, true)) return;
                    String returned = "FAILED";
                    try {
                        permit.close();
                        returned = "RETURNED";
                    } finally {
                        activity.leave();
                        recordDuration("provider.lease", tags.and("outcome", returned), acquired);
                    }
                };
            } catch (AnalysisStoppedException stopped) {
                outcome = "STOPPED";
                throw stopped;
            } finally {
                activity.waiting.decrementAndGet();
                recordDuration("provider.wait", tags.and("outcome", outcome), started);
            }
        };
    }

    private final class Activity {
        final AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), waiting = new AtomicInteger();
        Activity(String family, Tags tags, boolean provider) {
            Gauge.builder(PREFIX + family + (provider ? ".inflight" : ".running"), active, AtomicInteger::get).tags(tags).register(registry);
            Gauge.builder(PREFIX + family + ".concurrency.peak", peak, AtomicInteger::get).tags(tags).register(registry);
            if (provider) Gauge.builder(PREFIX + "provider.waiting", waiting, AtomicInteger::get).tags(tags).register(registry);
        }
        void enter() { peak.accumulateAndGet(active.incrementAndGet(), Math::max); }
        void leave() { active.decrementAndGet(); }
    }

    private static Tags taskTags(AnalysisTaskMessage task) { return envelopeTags(task.envelope()); }
    private static Tags envelopeTags(AnalysisEnvelope envelope) {
        String root = envelope.roots().size() == 1
                ? envelope.roots().getFirst().defaultCatalogueRoot() ? envelope.roots().getFirst().code() : "OTHER"
                : "GENERAL";
        return Tags.of("task_type", envelope.taskType().name(), "root", root);
    }
    private static String outcome(AnalysisCompletionMessage completion) {
        return switch (completion) {
            case SubtaxonomyAnalysisCompleted root -> root.outcome().name();
            case RelationAnalysisCompleted relation -> relation.outcome().name();
        };
    }
    private void recordDuration(String name, Tags tags, long started) {
        registry.timer(PREFIX + name, tags).record(Math.max(0, registry.config().clock().monotonicTime() - started), TimeUnit.NANOSECONDS);
    }
    private void recordAge(String name, Tags tags, Instant created) {
        long elapsed;
        Instant now = Instant.ofEpochMilli(registry.config().clock().wallTime());
        try { elapsed = Math.max(0, java.time.Duration.between(created, now).toMillis()); }
        catch (ArithmeticException overflow) { elapsed = created.isAfter(now) ? 0 : Long.MAX_VALUE; }
        registry.timer(PREFIX + name, tags).record(elapsed, TimeUnit.MILLISECONDS);
    }
}
