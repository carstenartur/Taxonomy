package com.taxonomy.backup;

import java.io.IOException;

/** Keeps cancellation, runtime limits and the writer fence live during read-only discovery. */
@FunctionalInterface
public interface BackupCheckpoint {
    void check() throws IOException;
}
