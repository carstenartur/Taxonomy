package com.taxonomy.analysis.service;

import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Per-run pressure history. A transient pre-GC peak warns; sustained pressure stops. */
public final class AnalysisMemoryGuard {
    private static final long MIB = 1024 * 1024;

    public record Policy(int warningPercent, int stopPercent, long minimumHeadroom,
                         long pressureMillis, long maximumDurationMillis) {
        public Policy {
            if (warningPercent < 1 || warningPercent >= stopPercent || stopPercent > 98
                    || minimumHeadroom < MIB || pressureMillis < 0 || maximumDurationMillis < 1) {
                throw new IllegalArgumentException("Invalid analysis memory/time policy");
            }
        }
    }

    public record Sample(long usedBytes, long maximumBytes) { }
    public record Reading(long usedBytes, long maximumBytes, long headroomBytes,
                          int percent, boolean warning) { }

    private final Policy policy;
    private final Supplier<Sample> source;
    private final LongSupplier clock;
    private final long started;
    private Long pressureSince;

    public AnalysisMemoryGuard(Policy policy, Supplier<Sample> source, LongSupplier clock) {
        this.policy = policy;
        this.source = source;
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    public static Sample heapSample() {
        var usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return new Sample(usage.getUsed(), usage.getMax());
    }

    public Reading reading() {
        Sample sample = source.get();
        if (sample.maximumBytes() <= 0 || sample.usedBytes() < 0) {
            return new Reading(sample.usedBytes(), sample.maximumBytes(), -1, -1, false);
        }
        long free = Math.max(0, sample.maximumBytes() - sample.usedBytes());
        int percent = (int) Math.min(100, 100.0 * sample.usedBytes() / sample.maximumBytes());
        return new Reading(sample.usedBytes(), sample.maximumBytes(), free, percent,
                percent >= policy.warningPercent() || free < policy.minimumHeadroom());
    }

    public Reading check() {
        long now = clock.getAsLong();
        if (now - started >= policy.maximumDurationMillis()) {
            throw new AnalysisStoppedException(AnalysisStoppedException.Reason.TIME_LIMIT);
        }
        Reading reading = reading();
        if (reading.percent() < 0) {
            pressureSince = null;
            return reading;
        }
        if (reading.percent() >= 98
                || reading.headroomBytes() <= Math.min(4 * MIB, policy.minimumHeadroom() / 4)) {
            throw new AnalysisStoppedException(AnalysisStoppedException.Reason.MEMORY_PRESSURE);
        }
        boolean critical = reading.percent() >= policy.stopPercent()
                || reading.headroomBytes() < policy.minimumHeadroom();
        if (!critical) {
            pressureSince = null;
        } else {
            if (pressureSince == null) pressureSince = now;
            if (now - pressureSince >= policy.pressureMillis()) {
                throw new AnalysisStoppedException(AnalysisStoppedException.Reason.MEMORY_PRESSURE);
            }
        }
        return reading;
    }
}
