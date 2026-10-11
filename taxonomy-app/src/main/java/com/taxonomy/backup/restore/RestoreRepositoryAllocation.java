package com.taxonomy.backup.restore;

import com.taxonomy.backup.BackupRepositoryKey;
import java.util.Objects;

/** Explicitly approved target routing; archive names never allocate physical storage. */
public record RestoreRepositoryAllocation(BackupRepositoryKey targetKey, String physicalStorageName) {
    public RestoreRepositoryAllocation {
        Objects.requireNonNull(targetKey);
        if (physicalStorageName == null || !physicalStorageName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
                || physicalStorageName.contains("..")
                || physicalStorageName.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
            throw new IllegalArgumentException("Invalid allocated repository storage name");
    }
}
