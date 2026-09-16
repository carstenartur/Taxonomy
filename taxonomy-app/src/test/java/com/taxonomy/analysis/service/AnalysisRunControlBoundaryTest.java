package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Deterministic call-boundary regressions: no real provider, sleep or forced GC. */
class AnalysisRunControlBoundaryTest {
    private static final long MIB = 1024 * 1024;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicLong clock = new AtomicLong();
    private final AtomicReference<AnalysisMemoryGuard.Sample> sample = new AtomicReference<>(
            new AnalysisMemoryGuard.Sample(20 * MIB, 100 * MIB));
    private final AnalysisRunControl.Observer observer = mock(AnalysisRunControl.Observer.class);
    private final AnalysisMemoryGuard guard = new AnalysisMemoryGuard(
            new AnalysisMemoryGuard.Policy(80, 92, 16 * MIB, 0, 1000), sample::get, clock::get);

    @AfterEach
    void noThreadStateLeaks() {
        Thread.interrupted();
        assertFalse(AnalysisRunControl.active());
    }

    @Test
    void healthyCallReturnsItsCompleteEvidence() {
        LlmCallDetail result = detail();
        try (var control = new AnalysisRunControl(observer, cancelled::get, guard)) {
            assertSame(result, AnalysisRunControl.call("MOCK", "CP", () -> result));
        }
        verify(observer).completed(anyLong(), same(result), anyLong());
        verify(observer, never()).failed(anyLong(), anyString(), anyLong());
        verify(observer, never()).stopped(any());
    }

    @Test
    void cancellationDuringTheFinalProviderCallRetainsReturnedEvidence() {
        assertStopAfterResponse(() -> cancelled.set(true), AnalysisStoppedException.Reason.CANCELLED);
    }

    @Test
    void elapsedDeadlineIsCheckedAfterTheFinalProviderCall() {
        assertStopAfterResponse(() -> clock.set(1000), AnalysisStoppedException.Reason.TIME_LIMIT);
    }

    @Test
    void exhaustedHeapReserveIsCheckedAfterTheFinalProviderCall() {
        assertStopAfterResponse(() -> sample.set(new AnalysisMemoryGuard.Sample(99 * MIB, 100 * MIB)),
                AnalysisStoppedException.Reason.MEMORY_PRESSURE);
    }

    @Test
    void interruptDuringTheProviderCallIsPreservedAndStopsFurtherWork() {
        assertStopAfterResponse(() -> Thread.currentThread().interrupt(), AnalysisStoppedException.Reason.CANCELLED);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void cooperativeStopInsideProviderIsNotReportedAsProviderFailure() {
        try (var control = new AnalysisRunControl(observer, cancelled::get, guard)) {
            AnalysisStoppedException stopped = assertThrows(AnalysisStoppedException.class,
                    () -> AnalysisRunControl.call("MOCK", "CP", () -> {
                        cancelled.set(true);
                        AnalysisRunControl.checkpoint();
                        throw new AssertionError("unreachable");
                    }));
            assertEquals(AnalysisStoppedException.Reason.CANCELLED, stopped.reason());
        }
        verify(observer, atLeastOnce()).stopped(AnalysisStoppedException.Reason.CANCELLED);
        verify(observer, never()).completed(anyLong(), any(), anyLong());
        verify(observer, never()).failed(anyLong(), anyString(), anyLong());
    }

    @Test
    void ordinaryProviderFailureRemainsAProviderFailure() {
        RuntimeException failure = new IllegalStateException("provider unavailable");
        try (var control = new AnalysisRunControl(observer, cancelled::get, guard)) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> AnalysisRunControl.call("MOCK", "CP", () -> { throw failure; })));
        }
        verify(observer).failed(anyLong(), eq("IllegalStateException"), anyLong());
        verify(observer, never()).stopped(any());
    }

    @Test
    void cancelledCallDoesNotLeaveAnEndlesslyRunningLogEntry() {
        var registry = new AnalysisProgressRegistry(new MockEnvironment());
        var scope = new WorkspaceContext("alice", "work-a", "draft", "repo-a");
        try (var handle = registry.open(null, "alice", scope, null)) {
            assertThrows(AnalysisStoppedException.class, () -> AnalysisRunControl.call("MOCK", "CP", () -> {
                registry.cancel(handle.id(), "alice", scope);
                AnalysisRunControl.checkpoint();
                throw new AssertionError("unreachable");
            }));
            var snapshot = registry.snapshot(handle.id(), "alice", scope);
            assertEquals("STOPPING", snapshot.phase());
            assertEquals("CANCELLED", snapshot.stopReason());
            assertEquals("STOPPED", snapshot.calls().getFirst().status());
            handle.finish("PARTIAL");
            assertEquals("CANCELLED", registry.snapshot(handle.id(), "alice", scope).status());
        }
    }

    private void assertStopAfterResponse(Runnable duringCall, AnalysisStoppedException.Reason expected) {
        LlmCallDetail result = detail();
        try (var control = new AnalysisRunControl(observer, cancelled::get, guard)) {
            AnalysisStoppedException stopped = assertThrows(AnalysisStoppedException.class,
                    () -> AnalysisRunControl.call("MOCK", "CP", () -> {
                        duringCall.run();
                        return result;
                    }));
            assertEquals(expected, stopped.reason());
            assertEquals(result.getScores(), stopped.partialScores());
            assertEquals(result.getReasons(), stopped.partialReasons());
        }
        verify(observer, atLeastOnce()).stopped(expected);
        verify(observer, never()).completed(anyLong(), any(), anyLong());
        verify(observer, never()).failed(anyLong(), anyString(), anyLong());
    }

    private static LlmCallDetail detail() {
        var detail = new LlmCallDetail();
        detail.setScores(Map.of("CP", 80));
        detail.setReasons(Map.of("CP", "completed evidence"));
        detail.setProvider("MOCK");
        return detail;
    }
}
