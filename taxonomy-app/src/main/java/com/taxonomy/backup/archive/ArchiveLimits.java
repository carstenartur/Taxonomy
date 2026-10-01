package com.taxonomy.backup.archive;

import com.taxonomy.backup.BackupLimits;
import java.time.Duration;

/** Independent compressed-file, expanded-data, entry, metadata and runtime budgets. */
public record ArchiveLimits(long maxArchiveBytes, long maxExpandedBytes, long maxEntryBytes,
                            int maxEntries, int maxCompressionRatio, Duration maxDuration) {
    public ArchiveLimits {
        if (maxArchiveBytes < 1 || maxExpandedBytes < 1 || maxEntryBytes < 1 || maxEntryBytes > maxExpandedBytes
                || maxEntries < 1 || maxEntries > BackupLimits.MAX_ITEMS || maxCompressionRatio < 1 || maxCompressionRatio > 1000
                || maxDuration == null || maxDuration.isNegative() || maxDuration.isZero() || maxDuration.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Invalid archive limits");
    }
    public static ArchiveLimits defaults() {
        return new ArchiveLimits(12L << 30, 10L << 30, 2L << 30, BackupLimits.MAX_ITEMS, 100, Duration.ofMinutes(30));
    }
}
