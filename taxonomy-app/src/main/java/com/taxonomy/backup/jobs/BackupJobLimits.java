package com.taxonomy.backup.jobs;

import java.time.Duration;

public record BackupJobLimits(int heavyConcurrency, int currentConcurrency, int queueCapacity,
                              Duration lease, Duration retention, long maxRetainedBytes, long maxTemporaryBytes) {
    // 10 GiB capture + streaming protection overhead/manifest + 12 GiB archive.
    public static final long TEMPORARY_BYTES_PER_JOB = 24L << 30;
    public BackupJobLimits(int heavy, int current, int queued, Duration lease, Duration retention, long retained) {
        this(heavy, current, queued, lease, retention, retained, 72L << 30);
    }
    public BackupJobLimits {
        if (heavyConcurrency < 1 || heavyConcurrency > 8 || currentConcurrency < 1 || currentConcurrency > 32
                || queueCapacity < 1 || queueCapacity > 1000 || lease == null || lease.compareTo(Duration.ofSeconds(1)) < 0
                || lease.compareTo(Duration.ofMinutes(30)) > 0 || retention == null || retention.compareTo(Duration.ofMinutes(1)) < 0
                || retention.compareTo(Duration.ofDays(30)) > 0 || maxRetainedBytes < 1 || maxTemporaryBytes < TEMPORARY_BYTES_PER_JOB)
            throw new IllegalArgumentException("Invalid backup job limits");
    }
    public static BackupJobLimits defaults() { return new BackupJobLimits(1, 2, 64, Duration.ofSeconds(30), Duration.ofHours(24), 64L << 30); }
}
