package com.taxonomy.analysis.service;

import com.taxonomy.dto.LlmCallDetail;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisRunControlTypedTest {
    private record Decision(String value, LlmCallDetail detail) { }

    @Test
    void oldAndTypedCallsUseTheSameObserverExactlyOnce() {
        var observer = mock(AnalysisRunControl.Observer.class);
        var guard = mock(AnalysisMemoryGuard.class);
        var detail = new LlmCallDetail();
        var decision = new Decision("DESCEND", detail);
        when(observer.started("provider", "node")).thenReturn(17L);
        try (var control = new AnalysisRunControl(observer, () -> false, guard)) {
            assertSame(decision, AnalysisRunControl.call("provider", "node", () -> decision, Decision::detail));
            assertSame(detail, AnalysisRunControl.call("provider", "node", () -> detail));
        }
        verify(observer, times(2)).started("provider", "node");
        verify(observer, times(2)).completed(eq(17L), same(detail), anyLong());
        verify(observer, never()).failed(anyLong(), anyString(), anyLong());
        assertFalse(AnalysisRunControl.active());
    }

    @Test
    void cancellationAfterTypedResponseRetainsExistingPartialEvidence() {
        var observer = mock(AnalysisRunControl.Observer.class);
        var cancelled = new AtomicBoolean();
        var detail = new LlmCallDetail();
        detail.setScores(Map.of("A", 7));
        try (var control = new AnalysisRunControl(observer, cancelled::get, mock(AnalysisMemoryGuard.class))) {
            var stopped = assertThrows(AnalysisStoppedException.class, () ->
                    AnalysisRunControl.call("provider", "node", () -> {
                        cancelled.set(true);
                        return new Decision("ACCEPT", detail);
                    }, Decision::detail));
            assertEquals(7, stopped.partialScores().get("A"));
        }
        verify(observer, never()).completed(anyLong(), any(), anyLong());
        verify(observer).stoppedAfterResponse(anyLong(), same(detail), anyLong(),
                eq(AnalysisStoppedException.Reason.CANCELLED));
    }
}
