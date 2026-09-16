package com.taxonomy.analysis.service;

import com.taxonomy.dto.AnalysisProvenance;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisDurableAdmissionTest {
    @Test void durableWorkWaitsForCapacityInsteadOfFailingItsClaim() throws Exception {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var scope = new WorkspaceContext("alice", "work-a", "draft", "repo-a");
        var reservations = new ArrayList<AnalysisProgressRegistry.Reservation>();
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                for (int i = 0; i < AnalysisProgressRegistry.MAX_ACTIVE; i++) {
                    reservations.add(registry.reserve(null, "alice", scope, null));
                }
                assertEquals(503, assertThrows(ResponseStatusException.class,
                        () -> registry.reserve(null, "alice", scope, null)).getStatusCode().value());
                var future = executor.submit(() -> {
                    started.countDown();
                    try (var run = registry.open(null, "alice", scope,
                            new AnalysisProvenance(1L, 2L, "snapshot", "portfolio:snapshot"))) {
                        assertTrue(AnalysisRunControl.active());
                        AnalysisRunControl.checkpoint();
                        run.finish("SUCCESS");
                        return registry.snapshot(run.id(), "alice", scope).status();
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> future.get(100, TimeUnit.MILLISECONDS),
                        "No transient capacity exception may escape into the durable worker");
                assertFalse(AnalysisRunControl.active(), "Waiting does not bind the caller thread");
                reservations.removeFirst().close();
                assertEquals("COMPLETED", future.get(5, TimeUnit.SECONDS));
                assertTrue(registry.recent("alice", scope, 1L, 2L).stream()
                        .allMatch(snapshot -> "COMPLETED".equals(snapshot.status())));
            } finally {
                reservations.forEach(AnalysisProgressRegistry.Reservation::close);
                executor.shutdownNow();
            }
        }
    }
    @Test void interruptingCapacityWaitDoesNotAllocateOrLeakThreadControl() throws Exception {
        var registry = new AnalysisProgressRegistry(new StandardEnvironment());
        var scope = new WorkspaceContext("alice", "work-a", "draft", "repo-a");
        var reservations = new ArrayList<AnalysisProgressRegistry.Reservation>();
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var outcome = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            entered.countDown();
            try (var ignored = registry.open(null, "alice", scope,
                    new AnalysisProvenance(1L, 2L, "snapshot", "portfolio:snapshot"))) {
                outcome.set(new AssertionError("Saturated durable work was admitted"));
            } catch (AnalysisStoppedException stop) {
                if (stop.reason() != AnalysisStoppedException.Reason.CANCELLED
                        || !Thread.currentThread().isInterrupted() || AnalysisRunControl.active()) {
                    outcome.set(new AssertionError("Wait cancellation leaked control or lost interruption"));
                }
            } catch (Throwable unexpected) { outcome.set(unexpected); }
            finally { stopped.countDown(); }
        });
        try {
            for (int i = 0; i < AnalysisProgressRegistry.MAX_ACTIVE; i++) {
                reservations.add(registry.reserve(null, "alice", scope, null));
            }
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertNull(outcome.get());
            assertEquals(AnalysisProgressRegistry.MAX_ACTIVE, registry.recent("alice", scope, null, null).size());
            assertTrue(registry.recent("alice", scope, 1L, 2L).isEmpty());
        } finally {
            worker.interrupt();
            reservations.forEach(AnalysisProgressRegistry.Reservation::close);
            worker.join(5000);
        }
    }

}
