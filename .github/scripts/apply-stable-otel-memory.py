#!/usr/bin/env python3
"""Apply robust steady-state OpenTelemetry memory measurement for issue #636."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "taxonomy-app/src/test/java/com/taxonomy/ObservabilityPerformanceIT.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def patch(source: str) -> str:
    source = replace_once(
        source,
        '''    private static final long HARD_MEMORY_DELTA_MIB = Long.getLong(
            "taxonomy.observability.performance.hard-memory-delta-mib", 256L);
''',
        '''    private static final long HARD_MEMORY_DELTA_MIB = Long.getLong(
            "taxonomy.observability.performance.hard-memory-delta-mib", 256L);
    private static final int MEMORY_STEADY_STATE_SAMPLES = Integer.getInteger(
            "taxonomy.observability.performance.memory-samples", 7);
    private static final long MEMORY_SAMPLE_INTERVAL_MILLIS = Long.getLong(
            "taxonomy.observability.performance.memory-sample-interval-millis", 200L);
''',
        "memory sampling constants",
    )

    source = replace_once(
        source,
        '''        long memoryDeltaMiB = Math.round(
                (alwaysOn.memoryPeakBytes() - baseline.memoryPeakBytes())
                        / (1024.0 * 1024.0));

        boolean p95HardLimit = p95Overhead > HARD_P95_PERCENT
''',
        '''        long memoryDeltaMiB = Math.round(
                (alwaysOn.memorySteadyStateBytes() - baseline.memorySteadyStateBytes())
                        / (1024.0 * 1024.0));
        long peakMemoryDeltaMiB = Math.round(
                (alwaysOn.memoryPeakBytes() - baseline.memoryPeakBytes())
                        / (1024.0 * 1024.0));

        boolean p95HardLimit = p95Overhead > HARD_P95_PERCENT
''',
        "steady-state budget calculation",
    )

    source = replace_once(
        source,
        '''        boolean memoryHardLimit = memoryDeltaMiB > HARD_MEMORY_DELTA_MIB;
        BudgetEvaluation budget = new BudgetEvaluation(
                INVESTIGATION_THRESHOLD_PERCENT,
                p95Overhead,
                sampledP95Overhead,
                startupOverhead,
                memoryDeltaMiB,
                p95Overhead > INVESTIGATION_THRESHOLD_PERCENT,
                p95HardLimit,
                startupHardLimit,
                memoryHardLimit,
                p95HardLimit || startupHardLimit || memoryHardLimit);
''',
        '''        boolean memoryHardLimit = memoryDeltaMiB > HARD_MEMORY_DELTA_MIB;
        boolean peakMemoryInvestigation = peakMemoryDeltaMiB > HARD_MEMORY_DELTA_MIB;
        BudgetEvaluation budget = new BudgetEvaluation(
                INVESTIGATION_THRESHOLD_PERCENT,
                p95Overhead,
                sampledP95Overhead,
                startupOverhead,
                memoryDeltaMiB,
                peakMemoryDeltaMiB,
                p95Overhead > INVESTIGATION_THRESHOLD_PERCENT || peakMemoryInvestigation,
                peakMemoryInvestigation,
                p95HardLimit,
                startupHardLimit,
                memoryHardLimit,
                p95HardLimit || startupHardLimit || memoryHardLimit);
''',
        "peak diagnostic and stable hard gate",
    )

    source = replace_once(
        source,
        '''        PerformanceReport report = new PerformanceReport(
                1,
''',
        '''        PerformanceReport report = new PerformanceReport(
                2,
''',
        "report schema version",
    )

    source = replace_once(
        source,
        '''            long memoryPeakBytes = readMemoryPeakBytes(application);
            latencyNanos.sort(Comparator.naturalOrder());
''',
        '''            MemoryMeasurement memory = readMemoryMeasurement(application);
            latencyNanos.sort(Comparator.naturalOrder());
''',
        "collect robust memory measurement",
    )

    source = replace_once(
        source,
        '''                    startupMillis,
                    memoryPeakBytes,
                    cpuMicros,
''',
        '''                    startupMillis,
                    memory.steadyStateBytes(),
                    memory.peakBytes(),
                    memory.steadyStateSamplesBytes(),
                    memory.source(),
                    cpuMicros,
''',
        "mode result memory evidence",
    )

    source = replace_once(
        source,
        '''    private static long readMemoryPeakBytes(GenericContainer<?> application) throws Exception {
''',
        '''    private static MemoryMeasurement readMemoryMeasurement(
            GenericContainer<?> application) throws Exception {
        List<Long> samples = new ArrayList<>(MEMORY_STEADY_STATE_SAMPLES);
        String source = null;
        for (int index = 0; index < MEMORY_STEADY_STATE_SAMPLES; index++) {
            MemorySample sample = readSteadyStateMemorySample(application);
            if (source == null) {
                source = sample.source();
            } else {
                assertThat(sample.source())
                        .as("steady-state memory source must remain stable")
                        .isEqualTo(source);
            }
            samples.add(sample.bytes());
            if (index + 1 < MEMORY_STEADY_STATE_SAMPLES) {
                Thread.sleep(MEMORY_SAMPLE_INTERVAL_MILLIS);
            }
        }
        return new MemoryMeasurement(
                source,
                medianBytes(samples),
                readMemoryPeakBytes(application),
                List.copyOf(samples));
    }

    private static MemorySample readSteadyStateMemorySample(
            GenericContainer<?> application) throws Exception {
        String command = """
                if [ -r /sys/fs/cgroup/memory.stat ]; then
                  value=$(awk '$1 == "anon" { print $2; exit }' /sys/fs/cgroup/memory.stat)
                  if [ -n "$value" ]; then
                    printf 'cgroup-v2-anon %s\\n' "$value"
                    exit 0
                  fi
                fi
                if [ -r /sys/fs/cgroup/memory/memory.stat ]; then
                  value=$(awk '$1 == "total_rss" { print $2; exit }' /sys/fs/cgroup/memory/memory.stat)
                  if [ -z "$value" ]; then
                    value=$(awk '$1 == "rss" { print $2; exit }' /sys/fs/cgroup/memory/memory.stat)
                  fi
                  if [ -n "$value" ]; then
                    printf 'cgroup-v1-rss %s\\n' "$value"
                    exit 0
                  fi
                fi
                if [ -r /proc/1/status ]; then
                  value=$(awk '$1 == "VmRSS:" { print $2 * 1024; exit }' /proc/1/status)
                  if [ -n "$value" ]; then
                    printf 'proc-vmrss %s\\n' "$value"
                    exit 0
                  fi
                fi
                if [ -r /sys/fs/cgroup/memory.current ]; then
                  printf 'cgroup-v2-current %s\\n' "$(cat /sys/fs/cgroup/memory.current)"
                  exit 0
                fi
                echo 'unavailable 0'
                """;
        Container.ExecResult result = application.execInContainer("sh", "-c", command);
        assertThat(result.getExitCode())
                .withFailMessage("Failed to read steady-state memory: %s", result.getStderr())
                .isZero();
        MemorySample sample = parseMemorySample(result.getStdout());
        assertThat(sample.source()).as("steady-state memory source").isNotEqualTo("unavailable");
        assertThat(sample.bytes()).as("steady-state memory bytes").isPositive();
        return sample;
    }

    static MemorySample parseMemorySample(String output) {
        String[] parts = output.trim().split("\\\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid memory sample: " + output);
        }
        try {
            return new MemorySample(parts[0], Long.parseLong(parts[1]));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid memory sample: " + output, exception);
        }
    }

    static long medianBytes(List<Long> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("At least one memory sample is required");
        }
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2.0);
    }

    private static long readMemoryPeakBytes(GenericContainer<?> application) throws Exception {
''',
        "robust memory helpers",
    )

    source = replace_once(
        source,
        '''                .append("| Mode | Startup ms | Memory peak MiB | CPU µs/request | p50 ms | p95 ms | Spans/request |\\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\\n");
        for (ModeResult mode : report.modes()) {
            markdown.append(String.format(Locale.ROOT,
                    "| %s | %d | %.1f | %.1f | %d | %d | %.2f |%n",
                    mode.name(), mode.startupMillis(),
                    mode.memoryPeakBytes() / (1024.0 * 1024.0),
                    mode.cpuMicrosPerRequest(), mode.p50Millis(), mode.p95Millis(),
                    mode.spansPerRequest()));
        }
''',
        '''                .append("| Mode | Startup ms | Steady-state median MiB | Lifetime peak MiB | Memory source | CPU µs/request | p50 ms | p95 ms | Spans/request |\\n")
                .append("|---|---:|---:|---:|---|---:|---:|---:|---:|\\n");
        for (ModeResult mode : report.modes()) {
            markdown.append(String.format(Locale.ROOT,
                    "| %s | %d | %.1f | %.1f | `%s` | %.1f | %d | %d | %.2f |%n",
                    mode.name(), mode.startupMillis(),
                    mode.memorySteadyStateBytes() / (1024.0 * 1024.0),
                    mode.memoryPeakBytes() / (1024.0 * 1024.0),
                    mode.memoryMeasurementSource(),
                    mode.cpuMicrosPerRequest(), mode.p50Millis(), mode.p95Millis(),
                    mode.spansPerRequest()));
        }
        markdown.append("\\n### Raw steady-state memory samples\\n\\n");
        for (ModeResult mode : report.modes()) {
            markdown.append("- `").append(mode.name()).append("` (`")
                    .append(mode.memoryMeasurementSource()).append("`): ")
                    .append(mode.memorySteadyStateSamplesBytes().stream()
                            .map(value -> String.format(Locale.ROOT, "%.1f MiB",
                                    value / (1024.0 * 1024.0)))
                            .toList())
                    .append("\\n");
        }
''',
        "markdown memory evidence",
    )

    source = replace_once(
        source,
        '''                .append("- Always-on memory delta: **").append(budget.memoryDeltaMiB())
                .append(" MiB**.\\n")
''',
        '''                .append("- Always-on steady-state median memory delta (hard gate): **")
                .append(budget.memoryDeltaMiB()).append(" MiB**.\\n")
                .append("- Always-on lifetime peak memory delta (diagnostic): **")
                .append(budget.peakMemoryDeltaMiB()).append(" MiB**.\\n")
                .append("- Lifetime peak requires investigation: **")
                .append(budget.peakMemoryInvestigationRequired()).append("**.\\n")
''',
        "markdown budget memory semantics",
    )

    source = replace_once(
        source,
        '''    private record ModeResult(
            String name,
            boolean agentAttached,
            String sampler,
            String samplerArgument,
            long startupMillis,
            long memoryPeakBytes,
            long cpuMicros,
''',
        '''    record MemorySample(String source, long bytes) {
    }

    private record MemoryMeasurement(
            String source,
            long steadyStateBytes,
            long peakBytes,
            List<Long> steadyStateSamplesBytes) {
    }

    private record ModeResult(
            String name,
            boolean agentAttached,
            String sampler,
            String samplerArgument,
            long startupMillis,
            long memorySteadyStateBytes,
            long memoryPeakBytes,
            List<Long> memorySteadyStateSamplesBytes,
            String memoryMeasurementSource,
            long cpuMicros,
''',
        "memory records",
    )

    source = replace_once(
        source,
        '''            double startupOverheadPercent,
            long memoryDeltaMiB,
            boolean investigationRequired,
            boolean p95HardLimitExceeded,
''',
        '''            double startupOverheadPercent,
            long memoryDeltaMiB,
            long peakMemoryDeltaMiB,
            boolean investigationRequired,
            boolean peakMemoryInvestigationRequired,
            boolean p95HardLimitExceeded,
''',
        "budget record memory fields",
    )
    return source


def main() -> None:
    source = TARGET.read_text(encoding="utf-8")
    if "memorySteadyStateSamplesBytes" in source:
        print("Stable OpenTelemetry memory measurement already applied.")
        return
    TARGET.write_text(patch(source), encoding="utf-8")
    print("Applied robust steady-state OpenTelemetry memory measurement.")


if __name__ == "__main__":
    main()
