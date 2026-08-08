#!/usr/bin/env python3
"""Apply the remaining fail-closed OpenTelemetry memory review fixes."""

from pathlib import Path

IT = Path("taxonomy-app/src/test/java/com/taxonomy/ObservabilityPerformanceIT.java")
TEST = Path("taxonomy-app/src/test/java/com/taxonomy/ObservabilityPerformanceMemoryTest.java")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


it = IT.read_text(encoding="utf-8")
it = replace_once(
    it,
    """    private static MemoryMeasurement readMemoryMeasurement(\n            GenericContainer<?> application) throws Exception {\n        List<Long> samples = new ArrayList<>(MEMORY_STEADY_STATE_SAMPLES);\n""",
    """    private static MemoryMeasurement readMemoryMeasurement(\n            GenericContainer<?> application) throws Exception {\n        validateMemorySamplingConfiguration(\n                MEMORY_STEADY_STATE_SAMPLES, MEMORY_SAMPLE_INTERVAL_MILLIS);\n        List<Long> samples = new ArrayList<>(MEMORY_STEADY_STATE_SAMPLES);\n""",
    "memory sampling entry point",
)
it = replace_once(
    it,
    """                  value=$(awk '$1 == \"VmRSS:\" { print $2 * 1024; exit }' /proc/1/status)\n""",
    """                  value=$(awk '$1 == \"VmRSS:\" { printf \"%.0f\\\\n\", $2 * 1024; exit }' /proc/1/status)\n""",
    "VmRSS conversion",
)
it = replace_once(
    it,
    """    static MemorySample parseMemorySample(String output) {\n""",
    """    static void validateMemorySamplingConfiguration(int samples, long intervalMillis) {\n        if (samples <= 0) {\n            throw new IllegalArgumentException(\n                    \"OpenTelemetry memory sample count must be greater than zero: \" + samples);\n        }\n        if (intervalMillis <= 0) {\n            throw new IllegalArgumentException(\n                    \"OpenTelemetry memory sample interval must be greater than zero: \"\n                            + intervalMillis);\n        }\n    }\n\n    static MemoryBudgetDecision evaluateMemoryBudget(\n            long steadyStateDeltaMiB, long peakDeltaMiB, long hardLimitMiB) {\n        return new MemoryBudgetDecision(\n                steadyStateDeltaMiB > hardLimitMiB,\n                peakDeltaMiB > hardLimitMiB);\n    }\n\n    static MemorySample parseMemorySample(String output) {\n""",
    "memory validation helpers",
)
it = replace_once(
    it,
    """        boolean memoryHardLimit = memoryDeltaMiB > HARD_MEMORY_DELTA_MIB;\n        boolean peakMemoryInvestigation = peakMemoryDeltaMiB > HARD_MEMORY_DELTA_MIB;\n""",
    """        MemoryBudgetDecision memoryBudget = evaluateMemoryBudget(\n                memoryDeltaMiB, peakMemoryDeltaMiB, HARD_MEMORY_DELTA_MIB);\n        boolean memoryHardLimit = memoryBudget.hardLimitExceeded();\n        boolean peakMemoryInvestigation = memoryBudget.peakInvestigationRequired();\n""",
    "memory budget evaluation",
)
it = replace_once(
    it,
    """    record MemorySample(String source, long bytes) {\n    }\n\n""",
    """    record MemorySample(String source, long bytes) {\n    }\n\n    record MemoryBudgetDecision(\n            boolean hardLimitExceeded,\n            boolean peakInvestigationRequired) {\n    }\n\n""",
    "memory budget decision record",
)
IT.write_text(it, encoding="utf-8")

test = TEST.read_text(encoding="utf-8")
test = replace_once(
    test,
    """    @Test\n    void parsesNamedMemorySamples() {\n""",
    """    @Test\n    void validatesMemorySamplingConfigurationBeforeAllocatingOrSleeping() {\n        assertThatIllegalArgumentException()\n                .isThrownBy(() -> ObservabilityPerformanceIT\n                        .validateMemorySamplingConfiguration(0, 200L))\n                .withMessageContaining(\"sample count must be greater than zero\");\n        assertThatIllegalArgumentException()\n                .isThrownBy(() -> ObservabilityPerformanceIT\n                        .validateMemorySamplingConfiguration(7, 0L))\n                .withMessageContaining(\"sample interval must be greater than zero\");\n        assertThatIllegalArgumentException()\n                .isThrownBy(() -> ObservabilityPerformanceIT\n                        .validateMemorySamplingConfiguration(7, -1L))\n                .withMessageContaining(\"sample interval must be greater than zero\");\n    }\n\n    @Test\n    void lifetimePeakAloneRequiresInvestigationWithoutFailingTheHardGate() {\n        ObservabilityPerformanceIT.MemoryBudgetDecision decision =\n                ObservabilityPerformanceIT.evaluateMemoryBudget(64L, 512L, 256L);\n\n        assertThat(decision.hardLimitExceeded()).isFalse();\n        assertThat(decision.peakInvestigationRequired()).isTrue();\n    }\n\n    @Test\n    void steadyStateRegressionFailsTheMemoryHardGate() {\n        ObservabilityPerformanceIT.MemoryBudgetDecision decision =\n                ObservabilityPerformanceIT.evaluateMemoryBudget(257L, 128L, 256L);\n\n        assertThat(decision.hardLimitExceeded()).isTrue();\n        assertThat(decision.peakInvestigationRequired()).isFalse();\n    }\n\n    @Test\n    void parsesNamedMemorySamples() {\n""",
    "memory contract tests",
)
TEST.write_text(test, encoding="utf-8")
print("Applied OpenTelemetry memory fallback, validation, and budget-decision fixes.")
