package com.taxonomy.backup.archive;

import java.io.IOException;

/** Checks cancellation and current worker ownership; receives cumulative bytes for this phase. */
@FunctionalInterface
public interface ArchiveProgress {
    void check(long bytes) throws IOException;
    ArchiveProgress NONE = bytes -> { };
}
