package com.taxonomy.analysis.cluster;

import com.taxonomy.analysis.dag.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClusterAnalysisSignalsTest {
    @Test void measuresFirstCooperativeObservationWithoutInventingReconnectTiming() {
        var requested = java.time.Instant.parse("2026-10-04T14:00:00Z");
        var hub = new ClusterAnalysisSignals(Clock.fixed(requested.plusMillis(75), java.time.ZoneOffset.UTC));
        var timings = new java.util.ArrayList<java.time.Duration>();
        hub.cancellationLatency(timings::add);
        var context = ClusterAnalysisStoreTest.context("cancel-latency");
        var event = new AnalysisMessageFactory(context, Clock.fixed(requested, java.time.ZoneOffset.UTC)).cancellation("CANCELLED");
        try (var worker = hub.worker(context)) {
            hub.cancellation(event);
            assertTrue(timings.isEmpty(), "Receipt is distinct from observation at a safe point");
            assertTrue(worker.cancelled());
            hub.cancellation(event);
            assertTrue(worker.cancelled());
            assertEquals(java.util.List.of(java.time.Duration.ofMillis(75)), timings);
        }
        hub.workerReconciliation(ignored -> true);
        try (var worker = hub.worker(context)) {
            hub.reconnected();
            assertTrue(worker.cancelled());
            assertEquals(1, timings.size(), "Reconnect has no original event timestamp");
        }
    }

    @org.junit.jupiter.api.Test void reconnectReconcilesMissedDurableStopsForRunningWorkers() {
        var signals = new ClusterAnalysisSignals();
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        signals.workerReconciliation(context -> { reads.incrementAndGet(); return cancelled.get(); });
        var context = ClusterAnalysisStoreTest.context("missed-cancel");
        try (var worker = signals.worker(context)) {
            signals.reconnected(); org.junit.jupiter.api.Assertions.assertFalse(worker.cancelled());
            cancelled.set(true);
            org.junit.jupiter.api.Assertions.assertFalse(worker.cancelled(), "No database polling between events");
            signals.reconnected(); org.junit.jupiter.api.Assertions.assertTrue(worker.cancelled());
            org.junit.jupiter.api.Assertions.assertEquals(2, reads.get());
        }
    }
    @Test void duplicateReverseAndForeignEventsCannotReachObservers() {
        var hub = new ClusterAnalysisSignals(); var context = ClusterAnalysisStoreTest.context("signals");
        var calls = new AtomicInteger();
        try (var subscription = hub.listen(context, event -> calls.incrementAndGet())) {
            var messages = new AnalysisMessageFactory(context, Clock.systemUTC());
            hub.progress(messages.progress(3, AnalysisProgressPhase.TASK_COMPLETED, null, 1, 2));
            hub.progress(messages.progress(2, AnalysisProgressPhase.TASK_COMPLETED, null, 1, 2));
            hub.progress(messages.progress(3, AnalysisProgressPhase.TASK_COMPLETED, null, 1, 2));
            var foreign = new AnalysisOperationContext(context.operationId(),
                    new AnalysisSourceAuthority("other", "workspace", "draft", "source"), context.requirement(), context.correlationId());
            hub.progress(new AnalysisMessageFactory(foreign, Clock.systemUTC()).progress(4, AnalysisProgressPhase.OPERATION_COMPLETED, null, 2, 2));
            assertEquals(1, calls.get());
            hub.progress(messages.progress(4, AnalysisProgressPhase.OPERATION_COMPLETED, null, 2, 2));
            assertEquals(2, calls.get());
        }
        assertEquals(0, hub.listenerCount());
    }

    @Test void cancellationIsScopedAndReachesEverySimultaneousDelivery() {
        var hub = new ClusterAnalysisSignals(); var context = ClusterAnalysisStoreTest.context("cancel-signals");
        try (var first = hub.worker(context); var duplicate = hub.worker(context)) {
            assertFalse(first.cancelled());
            hub.cancellation(new AnalysisMessageFactory(ClusterAnalysisStoreTest.context("other"), Clock.systemUTC()).cancellation("CANCELLED"));
            assertFalse(first.cancelled());
            hub.cancellation(new AnalysisMessageFactory(context, Clock.systemUTC()).cancellation("CANCELLED"));
            assertTrue(first.cancelled()); assertTrue(duplicate.cancelled());
        }
        assertEquals(0, hub.workerCount());
    }
}
