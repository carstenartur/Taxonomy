package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class AnalysisMemoryGuardTest {
    private static final long MIB = 1024 * 1024;
    private final AtomicLong time = new AtomicLong();
    private final AtomicReference<AnalysisMemoryGuard.Sample> memory =
            new AtomicReference<>(new AnalysisMemoryGuard.Sample(50 * MIB, 100 * MIB));
    private AnalysisMemoryGuard guard() {
        return new AnalysisMemoryGuard(new AnalysisMemoryGuard.Policy(80, 92, 8 * MIB, 5000, 60000),
                memory::get, time::get);
    }
    @Test void transientPressureWarnsWithoutCancelling() {
        var guard = guard();
        memory.set(new AnalysisMemoryGuard.Sample(94 * MIB, 100 * MIB));
        assertThat(guard.check().warning()).isTrue();
        time.set(4999);
        assertThatCode(guard::check).doesNotThrowAnyException();
        memory.set(new AnalysisMemoryGuard.Sample(60 * MIB, 100 * MIB));
        time.set(5001);
        assertThat(guard.check().warning()).isFalse();
    }
    @Test void sustainedPressureStopsBeforeNextAllocation() {
        var guard = guard();
        memory.set(new AnalysisMemoryGuard.Sample(94 * MIB, 100 * MIB));
        guard.check();
        time.set(5000);
        assertThatThrownBy(guard::check).isInstanceOf(AnalysisStoppedException.class)
                .hasMessageContaining("MEMORY_PRESSURE");
    }
    @Test void recoveryResetsThePressureInterval() {
        var guard = guard();
        memory.set(new AnalysisMemoryGuard.Sample(94 * MIB, 100 * MIB));
        guard.check();
        time.set(4000); memory.set(new AnalysisMemoryGuard.Sample(50 * MIB, 100 * MIB)); guard.check();
        time.set(5000); memory.set(new AnalysisMemoryGuard.Sample(94 * MIB, 100 * MIB)); guard.check();
        time.set(9000); assertThatCode(guard::check).doesNotThrowAnyException();
    }
    @Test void emergencyReserveStopsImmediately() {
        var guard=guard();
        memory.set(new AnalysisMemoryGuard.Sample(99 * MIB, 100 * MIB));
        assertThatThrownBy(guard::check).hasMessageContaining("MEMORY_PRESSURE");
    }
    @Test void unknownMaximumIsNotReportedAsZeroFreeMemory() {
        var guard=guard(); memory.set(new AnalysisMemoryGuard.Sample(50 * MIB, -1));
        assertThat(guard.check().percent()).isEqualTo(-1);
        assertThat(guard.check().warning()).isFalse();
    }
    @Test void deadlineIsMonotonicAndIndependentFromHeapPressure() {
        var guard=guard(); time.set(60000);
        assertThatThrownBy(guard::check).hasMessageContaining("TIME_LIMIT");
    }
    @Test void invalidThresholdsAreRejectedRatherThanDisablingProtection() {
        assertThatThrownBy(() -> new AnalysisMemoryGuard.Policy(95, 90, MIB, 5000, 60000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
