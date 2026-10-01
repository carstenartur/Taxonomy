package com.taxonomy.backup;

import java.util.UUID;
import java.util.Objects;

public record BackupId(UUID value) {
    public BackupId { Objects.requireNonNull(value, "value"); }
    public static BackupId create() { return new BackupId(UUID.randomUUID()); }
}
