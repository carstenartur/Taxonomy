package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Unit tests for the robust steady-state memory measurement used by the OTEL budget. */
class ObservabilityPerformanceMemoryTest {

    @Test
    void validatesMemorySamplingConfigurationBeforeAllocatingOrSleeping() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObservabilityPerformanceIT
                        .validateMemorySamplingConfiguration(0, 200L))
                .withMessageContaining("sample count must be greater than zero");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObservabilityPerformanceIT
                        .validateMemorySamplingConfiguration(7, 0L))
                .withMessageContaining("sample interval must be greater than zero");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObservabilityPerformanceIT
                        .validateMemorySamplingConfiguration(7, -1L))
                .withMessageContaining("sample interval must be greater than zero");
    }

    @Test
    void lifetimePeakAloneRequiresInvestigationWithoutFailingTheHardGate() {
        ObservabilityPerformanceIT.MemoryBudgetDecision decision =
                ObservabilityPerformanceIT.evaluateMemoryBudget(64L, 512L, 256L);

        assertThat(decision.hardLimitExceeded()).isFalse();
        assertThat(decision.peakInvestigationRequired()).isTrue();
    }

    @Test
    void steadyStateRegressionFailsTheMemoryHardGate() {
        ObservabilityPerformanceIT.MemoryBudgetDecision decision =
                ObservabilityPerformanceIT.evaluateMemoryBudget(257L, 128L, 256L);

        assertThat(decision.hardLimitExceeded()).isTrue();
        assertThat(decision.peakInvestigationRequired()).isFalse();
    }

    @Test
    void parsesNamedMemorySamples() {
        ObservabilityPerformanceIT.MemorySample sample =
                ObservabilityPerformanceIT.parseMemorySample("cgroup-v2-anon 1048576\n");

        assertThat(sample.source()).isEqualTo("cgroup-v2-anon");
        assertThat(sample.bytes()).isEqualTo(1_048_576L);
    }

    @Test
    void rejectsMalformedMemorySamples() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObservabilityPerformanceIT.parseMemorySample("invalid"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObservabilityPerformanceIT.parseMemorySample("proc-vmrss nope"));
    }

    @Test
    void selectsTheMedianWithoutLettingOneTransientPeakDominate() {
        assertThat(ObservabilityPerformanceIT.medianBytes(List.of(
                100L, 102L, 101L, 99L, 2_000L, 103L, 98L)))
                .isEqualTo(101L);
    }

    @Test
    void averagesTheTwoMiddleValuesForAnEvenSampleCount() {
        assertThat(ObservabilityPerformanceIT.medianBytes(List.of(20L, 10L, 40L, 30L)))
                .isEqualTo(25L);
    }

    @Test
    void requiresAtLeastOneSample() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ObservabilityPerformanceIT.medianBytes(List.of()));
    }
}
