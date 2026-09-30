package com.taxonomy.backup;

import java.util.Objects;
import java.util.UUID;

/** Opaque job identity; distinct from the capture identity created by a worker. */
public record BackupJobId(UUID value) {
    public BackupJobId { Objects.requireNonNull(value); }
    public static BackupJobId create() { return new BackupJobId(UUID.randomUUID()); }
}
