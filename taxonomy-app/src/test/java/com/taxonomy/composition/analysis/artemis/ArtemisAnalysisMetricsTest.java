package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.dag.*;
import com.taxonomy.analysis.service.AnalysisStoppedException;
import com.taxonomy.analysis.service.LlmProvider;
import com.taxonomy.analysis.service.ProviderConcurrencyPermits;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtemisAnalysisMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final ArtemisAnalysisMetrics metrics = new ArtemisAnalysisMetrics(registry);

    @Test void measuresQueueWaitExecutionOutcomesAndConcurrentRootWorkWithoutRunningEffects() {
        var task = task("private-operation", "CP");
        clock.add(Duration.ofSeconds(2));
        var effect = new AtomicInteger();
        var completed = completion(task, AnalysisTaskOutcome.PARTIAL);
        var prepared = new PreparedAnalysisCompletion<>(completed, effect::incrementAndGet);
        assertThat(metrics.prepare(task, () -> {
            assertThat(running("CP")).isEqualTo(1);
            metrics.prepare(task, () -> {
                assertThat(running("CP")).isEqualTo(2);
                clock.add(Duration.ofMillis(20));
                return prepared;
            });
            assertThat(running("CP")).isEqualTo(1);
            clock.add(Duration.ofMillis(30));
            return prepared;
        })).isSameAs(prepared);
        assertThat(effect).hasValue(0);
        assertThat(running("CP")).isZero();
        assertThat(registry.get("taxonomy.analysis.task.concurrency.peak").tag("root", "CP").gauge().value()).isEqualTo(2);
        assertThat(registry.get("taxonomy.analysis.task.queue.wait").timer().count()).isEqualTo(2);
        assertThat(registry.get("taxonomy.analysis.task.queue.wait").timer().totalTime(TimeUnit.SECONDS)).isEqualTo(4);
        assertThat(registry.get("taxonomy.analysis.task.execution").tag("outcome", "PARTIAL").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(70);
        assertThat(registry.get("taxonomy.analysis.task.outcomes").tag("outcome", "PARTIAL").counter().count()).isEqualTo(2);
    }

    @Test void failuresAndStopsReleaseRunningGaugeAndUseFiniteOutcomeTags() {
        var task = task("private-operation", "IP");
        metrics.prepare(task, () -> PreparedAnalysisCompletion.withoutEffects(completion(task, AnalysisTaskOutcome.STOPPED)));
        var failure = new IllegalStateException("private-provider-response");
        assertThatThrownBy(() -> metrics.prepare(task, () -> { clock.add(Duration.ofMillis(7)); throw failure; })).isSameAs(failure);
        assertThat(running("IP")).isZero();
        assertThat(registry.get("taxonomy.analysis.task.outcomes").tag("outcome", "STOPPED").counter().count()).isEqualTo(1);
        assertThat(registry.get("taxonomy.analysis.task.outcomes").tag("outcome", "FAILED").counter().count()).isEqualTo(1);
    }

    @Test void recordsPhasesAndSeparatesCompletionWaitFromDispatchAndCoordinatorTime() {
        var task = task("private-operation", "CP");
        var completed = completion(task, AnalysisTaskOutcome.COMPLETED);
        clock.add(Duration.ofMillis(100));
        metrics.coordination(completed, () -> {
            clock.add(Duration.ofMillis(20));
            metrics.dispatched(task, () -> clock.add(Duration.ofMillis(10)));
            clock.add(Duration.ofMillis(5));
        });
        var context = ClusterContext.of(task);
        metrics.progress(new AnalysisMessageFactory(context, Clock.systemUTC()).progress(1, AnalysisProgressPhase.OPERATION_COMPLETED, null, 1, 1));
        assertThat(registry.get("taxonomy.analysis.completion.queue.wait").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100);
        assertThat(registry.get("taxonomy.analysis.coordination.duration").tag("outcome", "COMPLETED").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(35);
        assertThat(registry.get("taxonomy.analysis.completion.to.dispatch").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(130);
        assertThat(registry.get("taxonomy.analysis.operation.events").tag("phase", "OPERATION_COMPLETED").counter().count()).isEqualTo(1);
        assertThatThrownBy(() -> metrics.coordination(completed, () -> { throw new IllegalStateException(); })).isInstanceOf(IllegalStateException.class);
        metrics.dispatched(task, () -> { });
        assertThat(registry.get("taxonomy.analysis.completion.to.dispatch").timer().count()).isEqualTo(1);
    }

    @Test void providerMetricsMeasureActualWaitAndLeaseAndCleanUpFailedReturns() {
        var settings = new ArtemisProviderPermitSettings(ArtemisProviderPermitSettings.DEFAULT_PREFIX, Map.of(LlmProvider.OPENAI, "shared-account"), 1000);
        var returns = new AtomicInteger();
        ProviderConcurrencyPermits decorated = metrics.permits((provider, checkpoint) -> {
            assertThat(registry.get("taxonomy.analysis.provider.waiting").gauge().value()).isEqualTo(1);
            checkpoint.run(); clock.add(Duration.ofMillis(40));
            return () -> { returns.incrementAndGet(); throw new IllegalStateException("private-return-error"); };
        }, settings);
        var permit = decorated.acquire(LlmProvider.OPENAI, () -> { });
        assertThat(registry.get("taxonomy.analysis.provider.waiting").gauge().value()).isZero();
        assertThat(registry.get("taxonomy.analysis.provider.inflight").gauge().value()).isEqualTo(1);
        clock.add(Duration.ofMillis(60));
        assertThatThrownBy(permit::close).isInstanceOf(IllegalStateException.class);
        permit.close();
        assertThat(returns).hasValue(1);
        assertThat(registry.get("taxonomy.analysis.provider.inflight").gauge().value()).isZero();
        assertThat(registry.get("taxonomy.analysis.provider.wait").tag("outcome", "ACQUIRED").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(40);
        assertThat(registry.get("taxonomy.analysis.provider.lease").tag("outcome", "FAILED").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(60);
        assertThatThrownBy(() -> decorated.acquire(LlmProvider.OPENAI, () -> { throw new AnalysisStoppedException(AnalysisStoppedException.Reason.CANCELLED); }))
                .isInstanceOf(AnalysisStoppedException.class);
        assertThat(registry.get("taxonomy.analysis.provider.waiting").gauge().value()).isZero();
        assertThat(registry.get("taxonomy.analysis.provider.wait").tag("outcome", "STOPPED").timer().count()).isEqualTo(1);
    }

    @Test void labelsNeverContainTaskIdentityOrArbitraryRootNames() {
        var task = task("private-operation", "CUSTOM_PRIVATE_ROOT");
        metrics.prepare(task, () -> PreparedAnalysisCompletion.withoutEffects(completion(task, AnalysisTaskOutcome.COMPLETED)));
        metrics.bindTransport(null, null);
        assertThat(registry.getMeters()).allSatisfy(meter -> meter.getId().getTags().forEach(tag -> {
            assertThat(Set.of("task_type", "root", "outcome", "phase", "provider", "quota_group")).contains(tag.getKey());
            assertThat(tag.getValue()).doesNotContain("private", "PRIVATE");
        }));
        assertThat(registry.get("taxonomy.analysis.task.running").tag("root", "OTHER").gauge().value()).isZero();
    }

    @Test void transportFunctionCountersReadCurrentMonotonicTotalsAndBindOnlyOnce() {
        // Only existing transport counter getters are stubbed; this test does not start a broker.
        var worker = mock(ArtemisAnalysisWorker.class);
        var coordinator = mock(ArtemisClusterCoordinator.class);
        var executions = new AtomicLong(2);
        when(worker.executions()).thenAnswer(call -> executions.get());
        when(worker.idempotentReplays()).thenReturn(3L);
        when(worker.rejected()).thenReturn(4L);
        when(worker.rolledBack()).thenReturn(5L);
        when(coordinator.accepted()).thenReturn(6L);
        when(coordinator.failures()).thenReturn(7L);
        when(coordinator.redeliveries()).thenReturn(8L);
        metrics.bindTransport(worker, coordinator);
        metrics.bindTransport(worker, coordinator);
        assertThat(registry.get("taxonomy.analysis.worker.executions").functionCounter().count()).isEqualTo(2);
        executions.set(9);
        assertThat(registry.get("taxonomy.analysis.worker.executions").functionCounter().count()).isEqualTo(9);
        assertThat(registry.get("taxonomy.analysis.worker.replays").functionCounter().count()).isEqualTo(3);
        assertThat(registry.get("taxonomy.analysis.worker.rejected").functionCounter().count()).isEqualTo(4);
        assertThat(registry.get("taxonomy.analysis.worker.rollbacks").functionCounter().count()).isEqualTo(5);
        assertThat(registry.get("taxonomy.analysis.coordinator.accepted").functionCounter().count()).isEqualTo(6);
        assertThat(registry.get("taxonomy.analysis.coordinator.failures").functionCounter().count()).isEqualTo(7);
        assertThat(registry.get("taxonomy.analysis.coordinator.redeliveries").functionCounter().count()).isEqualTo(8);
        assertThat(registry.getMeters()).hasSize(7);
    }

    @Test void cancellationObservationRecordsPositiveAndZeroLatencyAndClampsClockSkew() {
        metrics.cancellationObserved(Duration.ZERO);
        metrics.cancellationObserved(Duration.ofMillis(75));
        metrics.cancellationObserved(Duration.ofMillis(-30));
        var timer = registry.get("taxonomy.analysis.cancellation.observed").timer();
        assertThat(timer.count()).isEqualTo(3);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(75);
        assertThat(timer.getId().getTags()).isEmpty();
    }

    private double running(String root) { return registry.get("taxonomy.analysis.task.running").tag("root", root).gauge().value(); }
    private SubtaxonomyAnalysisTask task(String operation, String code) {
        var root = TaxonomyShardRoot.of(code);
        var context = new AnalysisOperationContext(operation, new AnalysisSourceAuthority("private-repo", "private-workspace", "private-branch", "private-commit"), RequirementReference.adHoc("private requirement"), operation);
        return (SubtaxonomyAnalysisTask) new AnalysisMessageFactory(context, Clock.fixed(Instant.ofEpochMilli(clock.wallTime()), ZoneOffset.UTC))
                .task(AnalysisTaskGraph.plan(operation, List.of(root), false).tasks().getFirst());
    }
    private SubtaxonomyAnalysisCompleted completion(SubtaxonomyAnalysisTask task, AnalysisTaskOutcome outcome) {
        return new AnalysisMessageFactory(ClusterContext.of(task), Clock.fixed(Instant.ofEpochMilli(clock.wallTime()), ZoneOffset.UTC))
                .completed(task, outcome, null, 0, outcome == AnalysisTaskOutcome.STOPPED ? "CANCELLED" : null);
    }
    private static final class ClusterContext {
        static AnalysisOperationContext of(AnalysisTaskMessage task) {
            var envelope = task.envelope();
            return new AnalysisOperationContext(envelope.operationId(), envelope.authority(), envelope.requirement(), envelope.correlationId());
        }
    }
}
