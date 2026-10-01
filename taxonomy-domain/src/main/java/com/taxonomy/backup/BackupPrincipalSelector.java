package com.taxonomy.backup;

import java.io.IOException;
import java.util.Set;

/** Principal IDs referenced by the authorized captured records, supplied by server-side composition. */
@FunctionalInterface
public interface BackupPrincipalSelector {
    Set<PrincipalId> select(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException;
}
