package com.taxonomy.backup;

import java.io.IOException;

/** Module-owned portable schema adapter. Implementations must honor the captured scope/profile. */
public interface BackupContributor {
    BackupComponentId componentId();
    int schemaVersion();
    void write(SnapshotContext snapshot, ComponentSink sink) throws IOException;
}
