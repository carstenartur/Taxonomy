package com.taxonomy.backup;

import java.io.IOException;
import java.util.Set;

/** Persisted ownership scopes selected by a module from the authorized captured records. */
@FunctionalInterface
public interface BackupPrincipalScopeSelector {
    Set<String> scopes(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException;
}
